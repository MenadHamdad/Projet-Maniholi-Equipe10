import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PlayerV1 extends Joueur {

    // ── Seuils d'énergie ──────────────────────────────────────────────────────
    private static final int SEUIL_ENERGIE_CRITIQUE  = 40;
    private static final int SEUIL_ENERGIE_PREVENTIF = 70;
    private static final int COUT_CAPTURE            = 20;
    private static final int MARGE_SECURITE          = 10;

    // ── Scoring moulins ───────────────────────────────────────────────────────
    private static final int    RAYON_CLUSTER = 5;
    private static final double POIDS_CLUSTER = 2.5;
    private static final double BONUS_ENNEMI  = 1.4;

    private final PlateauAnalyser plateauAnalyser;

    public PlayerV1(String sonNom) {
        super(sonNom);
        this.plateauAnalyser = new PlateauAnalyser();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Action faitUneAction(Plateau plateau) {
        plateauAnalyser.analysePlateau(plateau, this);

        final Point position = donnePosition();
        final int   energie  = donneRessources();

        // ── Priorité 1 : déjà sur une oliveraie et énergie basse → rester ─────
        if (estSurOliveraie(plateau, position) && energie < SEUIL_ENERGIE_PREVENTIF) {
            System.out.println("[PlayerV1] → RIEN sur oliveraie pour recharger");
            return Action.RIEN;
        }

        // ── Priorité 2 : énergie critique → aller sur une oliveraie ──────────
        if (energie <= SEUIL_ENERGIE_CRITIQUE) {
            Point oliveraie = trouveOliveraieProche(plateau, position);
            System.out.println("[PlayerV1] énergie critique, oliveraie cible=" + oliveraie);
            Action a = allerVers(plateau, position, oliveraie);
            if (a != null) return a;
        }

        // ── Priorité 3 : capturer le meilleur moulin ─────────────────────────
        Point cibleMoulin = trouveMeilleurMoulin(plateau, position, energie);
        if (cibleMoulin != null) {
            System.out.println("[PlayerV1] → moulin cible=" + cibleMoulin);
            Action a = allerVers(plateau, position, cibleMoulin);
            if (a != null) return a;
        }

        // ── Priorité 4 : recharge préventive ─────────────────────────────────
        if (energie < SEUIL_ENERGIE_PREVENTIF) {
            Point oliveraie = trouveOliveraieProche(plateau, position);
            System.out.println("[PlayerV1] recharge préventive vers=" + oliveraie);
            Action a = allerVers(plateau, position, oliveraie);
            if (a != null) return a;
        }

        System.out.println("[PlayerV1] → RIEN (aucune cible)");
        return Action.RIEN;
    }

    // =========================================================================
    //  NAVIGATION
    // =========================================================================

    /**
     * Retourne la première action à faire pour rejoindre la cible.
     *
     * IMPORTANT : AEtoile.donneChemin() retourne le chemin SANS le nœud de départ.
     *   - chemin vide (size 0)  → déjà sur la cible
     *   - chemin de size 1      → la cible est à 1 pas : chemin.get(0) EST la cible
     *   - chemin de size N      → chemin.get(0) est le prochain pas à faire
     */
    private Action allerVers(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null) return null;

        // Déjà sur la cible : rien à faire (la mécanique du jeu gère l'effet)
        if (depart.equals(cible)) return Action.RIEN;

        // Tentative avec évitement des autres joueurs
        ArrayList<Noeud> chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                depart, cible, obstaclesJoueurs(plateau));

        // Fallback sans évitement si chemin bloqué par les joueurs
        if (chemin == null || chemin.isEmpty()) {
            chemin = plateau.donneCheminEntre(depart, cible);
        }

        if (chemin == null || chemin.isEmpty()) {
            System.out.println("[PlayerV1] allerVers : chemin introuvable vers " + cible);
            return null;
        }

        // chemin.get(0) = prochain nœud à atteindre (départ exclu du chemin)
        Noeud prochainNoeud = chemin.get(0);
        Action action = directionVers(depart, prochainNoeud.getX(), prochainNoeud.getY());
        System.out.println("[PlayerV1] allerVers " + cible
                + " → prochain nœud " + prochainNoeud + " → " + action
                + " (chemin de " + chemin.size() + " pas)");
        return action;
    }

    // =========================================================================
    //  TROUVER LE MEILLEUR MOULIN
    // =========================================================================

    private Point trouveMeilleurMoulin(Plateau plateau, Point position, int energie) {
        final int taille        = plateau.donneTaille();
        final int toursRestants = plateau.donneNombreDeTours() - plateau.donneTourCourant();
        Point     meilleur      = null;
        double    meilleurScore = Double.NEGATIVE_INFINITY;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                // donneContenuCelluleSansJoueur : donne le vrai contenu de la case
                // (sans le masque de présence du joueur qui l'occupe)
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);

                boolean libre  = Plateau.contientUneUniteDeProductionLibre(contenu);
                boolean ennemi = Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu);
                if (!libre && !ennemi) continue;

                Point cible = new Point(x, y);

                // distance() retourne chemin.size() = nombre de pas réels à effectuer
                int dist = distance(plateau, position, cible);

                // dist == -1 : inaccessible
                // dist == 0  : déjà dessus (on est sur le moulin, la capture est déjà faite)
                if (dist < 0) continue;
                if (dist == 0) {
                    // On est déjà sur ce moulin, rien à faire pour cette case
                    continue;
                }

                // Énergie nécessaire : 1 par déplacement + 20 pour capturer + marge
                int energieNecessaire = dist + COUT_CAPTURE + MARGE_SECURITE;
                if (energieNecessaire > energie) continue;

                // Inutile d'y aller si on ne peut plus en profiter
                int toursGain = toursRestants - dist;
                if (toursGain <= 0) continue;

                // ── Score ──────────────────────────────────────────────────
                double score = toursGain;                                                   // bouteilles gagnées
                score += compteMoulinsProches(plateau, cible, RAYON_CLUSTER) * POIDS_CLUSTER; // bonus cluster
                if (ennemi) score *= BONUS_ENNEMI;                                          // prive l'adversaire
                score /= (dist + 1.0);                                                      // préférer les proches

                if (score > meilleurScore) {
                    meilleurScore = score;
                    meilleur      = cible;
                }
            }
        }
        return meilleur;
    }

    /**
     * Compte les moulins libres ou ennemis dans un rayon de Manhattan autour d'un point.
     * Un cluster de moulins proches vaut la peine car on peut les enchaîner.
     */
    private int compteMoulinsProches(Plateau plateau, Point centre, int rayon) {
        int count  = 0;
        int taille = plateau.donneTaille();
        for (int dy = -rayon; dy <= rayon; dy++) {
            for (int dx = -rayon; dx <= rayon; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = centre.x + dx, ny = centre.y + dy;
                if (!plateau.coordonneeValide(nx, ny)) continue;
                int c = plateau.donneContenuCelluleSansJoueur(nx, ny);
                if (Plateau.contientUneUniteDeProductionLibre(c)
                        || Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, c)) {
                    count++;
                }
            }
        }
        return count;
    }

    // =========================================================================
    //  OLIVERAIE
    // =========================================================================

    /**
     * Vérifie si la position actuelle est une oliveraie ($$).
     * On utilise donneContenuCelluleSansJoueur pour lire le vrai type de case,
     * car donneContenuCellule ajoute le masque de présence du joueur.
     */
    private boolean estSurOliveraie(Plateau plateau, Point position) {
        if (position == null) return false;
        int contenu = plateau.donneContenuCelluleSansJoueur(position.x, position.y);
        return Plateau.contientUneUniteDeRessourcage(contenu);
    }

    /**
     * Trouve l'oliveraie la plus proche en nombre de pas A*.
     */
    private Point trouveOliveraieProche(Plateau plateau, Point position) {
        if (position == null) return null;
        final int taille       = plateau.donneTaille();
        Point     meilleure    = null;
        int       meilleureDist = Integer.MAX_VALUE;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);
                if (!Plateau.contientUneUniteDeRessourcage(contenu)) continue;

                Point cible = new Point(x, y);

                // Si on est déjà dessus, c'est la meilleure : distance 0
                if (position.equals(cible)) return cible;

                int dist = distance(plateau, position, cible);
                if (dist <= 0 || dist >= meilleureDist) continue;

                // Vérifier qu'on a assez d'énergie pour y arriver
                if (dist > donneRessources()) continue;

                meilleureDist = dist;
                meilleure     = cible;
            }
        }
        return meilleure;
    }

    // =========================================================================
    //  UTILITAIRES
    // =========================================================================

    private int distance(Plateau plateau, Point depart, Point arrivee) {
        if (plateau == null || depart == null || arrivee == null) return -1;
        if (depart.equals(arrivee)) return 0;
        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, arrivee);
        if (chemin == null) return -1;
        return chemin.size(); // PAS chemin.size()-1 : le départ est exclu du chemin
    }

    private List<Noeud> obstaclesJoueurs(Plateau plateau) {
        List<Noeud> obstacles = new ArrayList<>();
        for (Joueur j : plateau.donneJoueurs()) {
            if (j == this || j.donnePosition() == null) continue;
            obstacles.add(new Noeud(j.donnePosition().x, j.donnePosition().y));
        }
        return obstacles;
    }

    private Action directionVers(Point depart, int nx, int ny) {
        int dx = nx - depart.x;
        int dy = ny - depart.y;
        if (dx > 0) return Action.DROITE;
        if (dx < 0) return Action.GAUCHE;
        if (dy > 0) return Action.BAS;
        if (dy < 0) return Action.HAUT;
        return Action.RIEN;
    }
}