import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PlayerV2 extends Joueur {

    // ── Seuils d'énergie ──────────────────────────────────────────────────────
    private static final int    SEUIL_ENERGIE_CRITIQUE   = 40;
    private static final int    SEUIL_ENERGIE_PREVENTIF  = 70;
    private static final int    COUT_CAPTURE             = 20;
    private static final int    MARGE_SECURITE           = 10;

    // ── Scoring moulins ───────────────────────────────────────────────────────
    private static final int    RAYON_CLUSTER            = 5;
    private static final double POIDS_CLUSTER            = 2.5;
    private static final double BONUS_ENNEMI             = 1.4;
    private static final double MALUS_JOUEUR_PROCHE      = 0.6; // diviseur si un adversaire est près du moulin
    private static final int    RAYON_DANGER_JOUEUR      = 3;   // cases pour considérer un moulin "contesté"

    // ── Oliveraie : séquence d'énergie gagnée par tour ────────────────────────
    // Tour 1 → +10, tour 2 → +20, tour 3 → +60, tour 4 → +20, tour 5 → +10
    private static final int[]  GAIN_OLIVERAIE           = {10, 20, 60, 20, 10};

    // ── État interne (réinitialisé à chaque partie via debutDePartie) ─────────
    private int toursEnOliveraieActuel = 0; // tours passés dans l'oliveraie en cours
    private int toursOptimaux          = 0; // tours qu'on a décidé de rester
    private boolean enTrainDeRecolter  = false;

    private final PlateauAnalyser plateauAnalyser;

    // ─────────────────────────────────────────────────────────────────────────

    public PlayerV2(String sonNom) {
        super(sonNom);
        this.plateauAnalyser = new PlateauAnalyser();
    }

    /**
     * Appelée par le MaitreDuJeu au début de chaque nouvelle partie.
     * OBLIGATOIRE pour que l'état interne soit remis à zéro entre les parties du tournoi.
     */
    @Override
    public void debutDePartie(int rang) {
        super.debutDePartie(rang);
        toursEnOliveraieActuel = 0;
        toursOptimaux          = 0;
        enTrainDeRecolter      = false;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Action faitUneAction(Plateau plateau) {
        plateauAnalyser.analysePlateau(plateau, this);

        final Point position      = donnePosition();
        final int   energie       = donneRessources();
        final int   tourCourant   = plateau.donneTourCourant();
        final int   toursRestants = plateau.donneNombreDeTours() - tourCourant;

        // ── Priorité 1 : on est en train de récolter dans une oliveraie ───────
        if (enTrainDeRecolter && estSurOliveraie(plateau, position)) {
            toursEnOliveraieActuel++;
            boolean rechargeComplete = energie >= 100
                    || toursEnOliveraieActuel >= toursOptimaux;
            if (!rechargeComplete) {
                return Action.RIEN;
            }
            // Récolte terminée : on repart
            enTrainDeRecolter      = false;
            toursEnOliveraieActuel = 0;
            toursOptimaux          = 0;
        } else if (enTrainDeRecolter && !estSurOliveraie(plateau, position)) {
            // On a été déplacé (ne devrait pas arriver, mais sécurité)
            enTrainDeRecolter = false;
        }

        // ── Priorité 2 : énergie critique → oliveraie d'urgence ──────────────
        if (energie <= SEUIL_ENERGIE_CRITIQUE) {
            Point oliveraie = trouveOliveraieProche(plateau, position, energie);
            if (oliveraie != null) {
                if (position.equals(oliveraie)) {
                    // On vient d'arriver : calculer combien de tours rester
                    int nbMoulins  = plateau.nombreDUnitesDeProductionJoueur(donneRang());
                    toursOptimaux  = calculerToursOptimaux(energie, nbMoulins, toursRestants);
                    enTrainDeRecolter      = true;
                    toursEnOliveraieActuel = 1;
                    return Action.RIEN;
                }
                Action a = allerVers(plateau, position, oliveraie);
                if (a != null) return a;
            }
        }

        // ── Priorité 3 : capturer le meilleur moulin ─────────────────────────
        Point cibleMoulin = trouveMeilleurMoulin(plateau, position, energie, toursRestants);
        if (cibleMoulin != null) {
            if (position.equals(cibleMoulin)) {
                // On est dessus (capture effectuée par le moteur) → chercher le suivant
                cibleMoulin = trouveMeilleurMoulin(plateau, position, energie, toursRestants);
            }
            Action a = allerVers(plateau, position, cibleMoulin);
            if (a != null) return a;
        }

        // ── Priorité 4 : recharge préventive si pas de moulin atteignable ─────
        if (energie < SEUIL_ENERGIE_PREVENTIF) {
            Point oliveraie = trouveOliveraieProche(plateau, position, energie);
            if (oliveraie != null) {
                if (position.equals(oliveraie)) {
                    int nbMoulins = plateau.nombreDUnitesDeProductionJoueur(donneRang());
                    toursOptimaux = calculerToursOptimaux(energie, nbMoulins, toursRestants);
                    enTrainDeRecolter      = true;
                    toursEnOliveraieActuel = 1;
                    return Action.RIEN;
                }
                Action a = allerVers(plateau, position, oliveraie);
                if (a != null) return a;
            }
        }

        return Action.RIEN;
    }

    // =========================================================================
    //  DURÉE OPTIMALE EN OLIVERAIE
    // =========================================================================

    /**
     * Calcule le nombre de tours qu'il est rentable de passer dans l'oliveraie.
     *
     * Logique : on simule chaque tour de récolte. On s'arrête dès que :
     *   - l'énergie est pleine (inutile de rester),
     *   - ou le coût en bouteilles perdues dépasse le gain énergétique.
     *
     * Coût d'un tour en oliveraie = nbMoulins bouteilles perdues.
     * Bénéfice = énergie gagnée, convertie en valeur future :
     *   chaque point d'énergie permet ~1 déplacement supplémentaire vers un moulin.
     *   On valorise un point d'énergie à (toursRestants / 100.0) bouteilles
     *   (approximation : plus on est loin de la fin, plus l'énergie vaut cher).
     *
     * On s'arrête toujours au maximum à 5 tours (séquence complète).
     */
    private int calculerToursOptimaux(int energieActuelle, int nbMoulins, int toursRestants) {
        double valeurEnergie = toursRestants / 100.0; // valeur d'un point d'énergie en bouteilles
        int    energieSim    = energieActuelle;
        int    toursRester   = 0;

        for (int i = 0; i < GAIN_OLIVERAIE.length; i++) {
            if (energieSim >= 100) break; // énergie pleine, inutile de rester

            int gainReel    = Math.min(GAIN_OLIVERAIE[i], 100 - energieSim); // cappé à 100
            double benefice = gainReel * valeurEnergie;                       // valeur en bouteilles
            double cout     = nbMoulins;                                      // bouteilles perdues ce tour

            // On reste si le bénéfice dépasse le coût,
            // ou si on est en urgence (énergie très basse) pour les 3 premiers tours
            if (benefice >= cout || (energieSim < 50 && i < 3)) {
                toursRester++;
                energieSim += gainReel;
            } else {
                break;
            }
        }

        // Toujours rester au moins 1 tour si on est arrivé ici
        return Math.max(1, toursRester);
    }

    // =========================================================================
    //  SCORING : MEILLEUR MOULIN
    // =========================================================================

    /**
     * Évalue chaque moulin accessible et retourne la meilleure cible.
     *
     * Formule de score :
     *   score = [ (toursRestants - dist) + cluster×2.5 + chaineLookahead ]
     *           × bonusEnnemi × malusConteste
     *           / (dist + 1)
     *
     * Chaîne lookahead : depuis le moulin cible, quel est le prochain moulin
     * le plus proche ? On ajoute les tours qu'on pourrait gagner en y allant.
     * Cela permet de départager deux moulins à distance égale.
     *
     * Malus contesté : si un adversaire est à moins de RAYON_DANGER_JOUEUR cases
     * de ce moulin, son score est multiplié par MALUS_JOUEUR_PROCHE (0.6).
     * Cela évite de foncé vers un moulin qu'un ennemi est en train de capturer.
     */
    private Point trouveMeilleurMoulin(Plateau plateau, Point position,
                                       int energie, int toursRestants) {
        final int taille   = plateau.donneTaille();
        Point     meilleur = null;
        double    meilleурScore = Double.NEGATIVE_INFINITY;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);

                boolean libre  = Plateau.contientUneUniteDeProductionLibre(contenu);
                boolean ennemi = Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu);
                if (!libre && !ennemi) continue;

                Point cible = new Point(x, y);
                int   dist  = distance(plateau, position, cible);
                if (dist <= 0) continue;

                // Énergie suffisante pour y aller + capturer + garder une marge
                if (dist + COUT_CAPTURE + MARGE_SECURITE > energie) continue;

                int toursGain = toursRestants - dist;
                if (toursGain <= 0) continue;

                // ── Score de base ─────────────────────────────────────────────
                double score = toursGain;

                // Bonus cluster (moulins proches = future chaîne de captures)
                score += compteMoulinsProches(plateau, cible, RAYON_CLUSTER) * POIDS_CLUSTER;

                // Lookahead : quel est le prochain moulin atteignable depuis ici ?
                score += scoreChaineDepuis(plateau, cible, energie - dist - COUT_CAPTURE,
                        toursRestants - dist);

                // Bonus moulin ennemi
                if (ennemi) score *= BONUS_ENNEMI;

                // Malus si un adversaire est proche de ce moulin (il va probablement le prendre)
                if (adversaireProcheDeCase(plateau, cible, RAYON_DANGER_JOUEUR)) {
                    score *= MALUS_JOUEUR_PROCHE;
                }

                // Normalisation par la distance
                score /= (dist + 1.0);

                if (score > meilleурScore) {
                    meilleурScore = score;
                    meilleur      = cible;
                }
            }
        }
        return meilleur;
    }

    /**
     * Lookahead d'un niveau : depuis une case donnée, quel est le score
     * du meilleur moulin atteignable avec l'énergie et les tours restants ?
     * Retourne 0 si aucun moulin n'est accessible.
     */
    private double scoreChaineDepuis(Plateau plateau, Point depart,
                                     int energieApres, int toursApres) {
        if (energieApres <= 0 || toursApres <= 0) return 0;

        final int taille      = plateau.donneTaille();
        double    meilleurScore = 0;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);
                boolean libre  = Plateau.contientUneUniteDeProductionLibre(contenu);
                boolean ennemi = Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu);
                if (!libre && !ennemi) continue;

                Point cible = new Point(x, y);
                if (cible.equals(depart)) continue;

                int dist = distance(plateau, depart, cible);
                if (dist <= 0) continue;
                if (dist + COUT_CAPTURE > energieApres) continue;

                int toursGain = toursApres - dist;
                if (toursGain <= 0) continue;

                double s = toursGain / (double)(dist + 1);
                if (s > meilleurScore) meilleurScore = s;
            }
        }
        return meilleurScore * 0.5; // on pondère le lookahead à 50% (incertitude)
    }

    /**
     * Vérifie si un adversaire se trouve à moins de 'rayon' cases (Manhattan)
     * d'une case donnée. Utilisé pour le malus de moulin contesté.
     */
    private boolean adversaireProcheDeCase(Plateau plateau, Point cible, int rayon) {
        for (Joueur j : plateau.donneJoueurs()) {
            if (j == this || j.donnePosition() == null) continue;
            Point pj = j.donnePosition();
            int dist = Math.abs(pj.x - cible.x) + Math.abs(pj.y - cible.y);
            if (dist <= rayon) return true;
        }
        return false;
    }

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

    private boolean estSurOliveraie(Plateau plateau, Point position) {
        if (position == null) return false;
        return Plateau.contientUneUniteDeRessourcage(
                plateau.donneContenuCelluleSansJoueur(position.x, position.y));
    }

    private Point trouveOliveraieProche(Plateau plateau, Point position, int energie) {
        if (position == null) return null;
        final int taille        = plateau.donneTaille();
        Point     meilleure     = null;
        int       meilleureDist = Integer.MAX_VALUE;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                if (!Plateau.contientUneUniteDeRessourcage(
                        plateau.donneContenuCelluleSansJoueur(x, y))) continue;

                Point cible = new Point(x, y);
                if (position.equals(cible)) return cible; // déjà dessus

                int dist = distance(plateau, position, cible);
                if (dist <= 0 || dist >= meilleureDist) continue;
                if (dist > energie) continue; // pas assez d'énergie pour y aller

                meilleureDist = dist;
                meilleure     = cible;
            }
        }
        return meilleure;
    }

    // =========================================================================
    //  NAVIGATION
    // =========================================================================

    /**
     * Retourne la première action A* vers la cible, en évitant les autres joueurs.
     * Fallback sans évitement si chemin bloqué par les joueurs.
     *
     * AEtoile.donneChemin() exclut le nœud de départ :
     *   chemin.get(0) = premier pas à faire
     *   chemin.size() = distance en nombre de pas
     */
    private Action allerVers(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null)  return null;
        if (depart.equals(cible))             return Action.RIEN;

        ArrayList<Noeud> chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                depart, cible, obstaclesJoueurs(plateau));

        if (chemin == null || chemin.isEmpty()) {
            chemin = plateau.donneCheminEntre(depart, cible);
        }
        if (chemin == null || chemin.isEmpty()) return null;

        Noeud suivant = chemin.get(0);
        return directionVers(depart, suivant.getX(), suivant.getY());
    }

    private List<Noeud> obstaclesJoueurs(Plateau plateau) {
        List<Noeud> obstacles = new ArrayList<>();
        for (Joueur j : plateau.donneJoueurs()) {
            if (j == this || j.donnePosition() == null) continue;
            obstacles.add(new Noeud(j.donnePosition().x, j.donnePosition().y));
        }
        return obstacles;
    }

    // =========================================================================
    //  UTILITAIRES
    // =========================================================================

    private int distance(Plateau plateau, Point depart, Point arrivee) {
        if (plateau == null || depart == null || arrivee == null) return -1;
        if (depart.equals(arrivee)) return 0;
        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, arrivee);
        return (chemin == null) ? -1 : chemin.size();
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