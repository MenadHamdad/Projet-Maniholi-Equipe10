import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

public class JoueurMo extends Joueur {

    // ── Énergie ───────────────────────────────────────────────────────────────
    private static final int SEUIL_ENERGIE_CRITIQUE   = 35;
    private static final int SEUIL_ENERGIE_RECHARGE   = 75;
    private static final int COUT_CAPTURE             = 20;
    private static final int MARGE_SECURITE           = 10;

    // ── Objectif ──────────────────────────────────────────────────────────────
    private static final int TYPE_AUCUN     = 0;
    private static final int TYPE_RESSOURCE = 1;
    private static final int TYPE_MOULIN    = 2;

    // ── Domination de zone ────────────────────────────────────────────────────
    private static final int OBJECTIF_MOULINS_COIN = 3;
    private static final int RAYON_CLUSTER = 5;

    // ── Scoring ───────────────────────────────────────────────────────────────
    private static final double BONUS_COIN                = 1800.0;
    private static final double BONUS_COIN_LIBRE          = 1500.0;
    private static final double BONUS_COIN_ENNEMI         = 1700.0;
    private static final double BONUS_HORS_COIN_PENALITE  = 1200.0;
    private static final double BONUS_CLUSTER             = 140.0;
    private static final double BONUS_VOISIN_MOI          = 500.0;
    private static final double BONUS_ATTAQUE_LEADER      = 900.0;
    private static final double BONUS_VOL                 = 350.0;
    private static final double PENALITE_DISTANCE         = 55.0;
    private static final double PENALITE_CONTESTATION     = 600.0;
    private static final double PENALITE_ADVERSAIRE_PLUS_PROCHE = 1300.0;
    private static final double BONUS_ENCHAINEMENT        = 180.0;

    private final PlateauAnalyser plateauAnalyser;

    private Point objectifCourant;
    private int typeObjectifCourant;
    private int secteurDepart = -1;

    private Point dernierePosition;
    private int toursBloque;

    public JoueurMo(String sonNom) {
        super(sonNom);
        this.plateauAnalyser = new PlateauAnalyser();
        this.objectifCourant = null;
        this.typeObjectifCourant = TYPE_AUCUN;
        this.dernierePosition = null;
        this.toursBloque = 0;
    }

    @Override
    public Action faitUneAction(Plateau plateau) {
        try {
            plateauAnalyser.analysePlateau(plateau, this);

            Point position = donnePosition();
            int energie = donneRessources();
            int monRang = donneRang();

            if (plateau == null || position == null || monRang < 0) {
                return Action.RIEN;
            }

            if (secteurDepart == -1) {
                secteurDepart = secteurDe(position, plateau.donneTaille());
            }

            mettreAJourBlocage(position);

            if (objectifCourant != null && position.equals(objectifCourant)) {
                reinitialiserObjectif();
            }

            Point meilleureRessource = trouveOliveraieStrategique(plateau, position, monRang);
            Point meilleurMoulin = trouveMeilleurMoulinTournoi(plateau, position, energie, monRang);

            int contenuActuel = plateau.donneContenuCelluleSansJoueur(position.x, position.y);

            // Si on est sur une oliveraie, on recharge tant qu'on n'a pas de quoi partir vraiment
            if (Plateau.contientUneUniteDeRessourcage(contenuActuel)) {
                if (meilleurMoulin == null) {
                    if (energie < 100) {
                        return Action.RIEN;
                    }
                } else {
                    int dist = distance(plateau, position, meilleurMoulin);
                    if (dist != -1) {
                        int energieNecessaire = dist + COUT_CAPTURE + MARGE_SECURITE;
                        if (energie < energieNecessaire || energie < SEUIL_ENERGIE_RECHARGE) {
                            return Action.RIEN;
                        }
                    }
                }
            }

            if (!objectifEncoreValide(plateau, position, energie, monRang)) {
                choisirNouvelObjectif(plateau, position, energie, monRang, meilleureRessource, meilleurMoulin);
            }

            if (energie <= SEUIL_ENERGIE_CRITIQUE && meilleureRessource != null) {
                fixeObjectif(meilleureRessource, TYPE_RESSOURCE);
            }

            // Si bloqué plusieurs tours, on jette l'objectif
            if (toursBloque >= 2) {
                reinitialiserObjectif();
                choisirNouvelObjectif(plateau, position, energie, monRang, meilleureRessource, meilleurMoulin);
            }

            // Suivre l'objectif stable
            if (objectifCourant != null) {
                Action a = allerVers(plateau, position, objectifCourant);
                if (a != null && !collisionProbableApresAction(plateau, position, a, monRang)) {
                    return a;
                }

                reinitialiserObjectif();
                choisirNouvelObjectif(plateau, position, energie, monRang, meilleureRessource, meilleurMoulin);

                if (objectifCourant != null) {
                    a = allerVers(plateau, position, objectifCourant);
                    if (a != null && !collisionProbableApresAction(plateau, position, a, monRang)) {
                        return a;
                    }
                }
            }

            // Tentatives directes
            if (meilleurMoulin != null) {
                Action a = allerVers(plateau, position, meilleurMoulin);
                if (a != null && !collisionProbableApresAction(plateau, position, a, monRang)) {
                    return a;
                }
            }

            if (meilleureRessource != null) {
                Action a = allerVers(plateau, position, meilleureRessource);
                if (a != null && !collisionProbableApresAction(plateau, position, a, monRang)) {
                    return a;
                }
            }

            return mouvementDeSecours(plateau, position, monRang);

        } catch (Exception e) {
            return Action.RIEN;
        }
    }

    // =========================================================================
    // CHOIX OBJECTIF
    // =========================================================================

    private void choisirNouvelObjectif(Plateau plateau, Point position, int energie, int monRang,
                                       Point meilleureRessource, Point meilleurMoulin) {

        if (meilleurMoulin != null) {
            int dist = distance(plateau, position, meilleurMoulin);
            if (dist != -1) {
                int energieNecessaire = dist + COUT_CAPTURE + MARGE_SECURITE;
                if (energie >= energieNecessaire) {
                    fixeObjectif(meilleurMoulin, TYPE_MOULIN);
                    return;
                }
            }
        }

        if (meilleureRessource != null) {
            fixeObjectif(meilleureRessource, TYPE_RESSOURCE);
            return;
        }

        if (meilleurMoulin != null) {
            fixeObjectif(meilleurMoulin, TYPE_MOULIN);
            return;
        }

        reinitialiserObjectif();
    }

    private boolean objectifEncoreValide(Plateau plateau, Point position, int energie, int monRang) {
        if (objectifCourant == null) {
            return false;
        }

        if (!plateau.coordonneeValide(objectifCourant.x, objectifCourant.y)) {
            return false;
        }

        int contenu = plateau.donneContenuCelluleSansJoueur(objectifCourant.x, objectifCourant.y);

        if (typeObjectifCourant == TYPE_RESSOURCE) {
            return Plateau.contientUneUniteDeRessourcage(contenu)
                    && distance(plateau, position, objectifCourant) != -1;
        }

        if (typeObjectifCourant == TYPE_MOULIN) {
            boolean libre = Plateau.contientUneUniteDeProductionLibre(contenu);
            boolean ennemi = Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu);

            if (!libre && !ennemi) {
                return false;
            }

            int dist = distance(plateau, position, objectifCourant);
            if (dist <= 0) {
                return false;
            }

            int energieNecessaire = dist + COUT_CAPTURE + MARGE_SECURITE;
            return energie >= energieNecessaire;
        }

        return false;
    }

    private void fixeObjectif(Point objectif, int typeObjectif) {
        if (objectif == null) {
            reinitialiserObjectif();
            return;
        }

        this.objectifCourant = new Point(objectif.x, objectif.y);
        this.typeObjectifCourant = typeObjectif;
    }

    private void reinitialiserObjectif() {
        this.objectifCourant = null;
        this.typeObjectifCourant = TYPE_AUCUN;
    }

    // =========================================================================
    // STRATÉGIE TOURNOI
    // =========================================================================

    private Point trouveMeilleurMoulinTournoi(Plateau plateau, Point position, int energie, int monRang) {
        int taille = plateau.donneTaille();
        int toursRestants = plateau.donneNombreDeTours() - plateau.donneTourCourant();

        Point meilleur = null;
        double meilleurScore = Double.NEGATIVE_INFINITY;

        int leader = rangLeader(plateau, monRang);
        int mesMoulinsCoin = compterMesMoulinsDansSecteur(plateau, monRang, secteurDepart);
        boolean coinVerrouille = mesMoulinsCoin >= OBJECTIF_MOULINS_COIN;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);

                boolean libre = Plateau.contientUneUniteDeProductionLibre(contenu);
                boolean ennemi = Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu);

                if (!libre && !ennemi) {
                    continue;
                }

                Point cible = new Point(x, y);
                int dist = distance(plateau, position, cible);

                if (dist <= 0) {
                    continue;
                }

                int energieNecessaire = dist + COUT_CAPTURE + MARGE_SECURITE;
                if (energieNecessaire > energie) {
                    continue;
                }

                int toursGain = toursRestants - dist;
                if (toursGain <= 0) {
                    continue;
                }

                int proprio = Plateau.donneUtilisateurDeLUniteDeProduction(contenu);
                int secteurCible = secteurDe(cible, taille);
                boolean memeCoin = (secteurCible == secteurDepart);

                double score = 0.0;

                // Base rentable
                score += toursGain * 8.0;
                score += compteMoulinsProches(plateau, cible, RAYON_CLUSTER) * BONUS_CLUSTER;
                score += bonusEnchainement(plateau, cible, monRang);

                if (estVoisinDeMesMoulins(plateau, cible, monRang)) {
                    score += BONUS_VOISIN_MOI;
                }

                // Monopole du coin avant tout
                if (memeCoin) {
                    score += BONUS_COIN;
                }

                if (memeCoin && libre) {
                    score += BONUS_COIN_LIBRE;
                }

                if (memeCoin && ennemi) {
                    score += BONUS_COIN_ENNEMI;
                }

                // Tant que le coin n'est pas verrouillé, on évite l'extérieur
                if (!memeCoin && !coinVerrouille) {
                    score -= BONUS_HORS_COIN_PENALITE;
                }

                // Vol agressif ensuite
                if (ennemi) {
                    score += BONUS_VOL;
                }

                // Casser le leader
                if (ennemi && proprio - 1 == leader) {
                    score += BONUS_ATTAQUE_LEADER;
                }

                // Distance
                score -= dist * PENALITE_DISTANCE;

                // Contestation
                double contestation = risqueContestation(plateau, cible, monRang, dist);
                score -= contestation * PENALITE_CONTESTATION;

                // Si un adversaire est plus proche, on sabre la cible
                if (adversairePlusProche(plateau, cible, monRang, dist)) {
                    score -= PENALITE_ADVERSAIRE_PLUS_PROCHE;
                }

                // Petit bonus si on reste dans une chaîne locale
                if (memeCoin && mesMoulinsCoin < OBJECTIF_MOULINS_COIN) {
                    score += 600.0;
                }

                if (score > meilleurScore) {
                    meilleurScore = score;
                    meilleur = cible;
                }
            }
        }

        return meilleur;
    }

    private int compteMoulinsProches(Plateau plateau, Point centre, int rayon) {
        int count = 0;
        int taille = plateau.donneTaille();

        for (int dy = -rayon; dy <= rayon; dy++) {
            for (int dx = -rayon; dx <= rayon; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }

                int nx = centre.x + dx;
                int ny = centre.y + dy;

                if (!plateau.coordonneeValide(nx, ny)) {
                    continue;
                }

                int contenu = plateau.donneContenuCelluleSansJoueur(nx, ny);

                if (Plateau.contientUneUniteDeProductionLibre(contenu)
                        || Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu)) {
                    count++;
                }
            }
        }

        return count;
    }

    private double bonusEnchainement(Plateau plateau, Point centre, int monRang) {
        int[][] dirs = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };

        double bonus = 0.0;

        for (int[] d : dirs) {
            int nx = centre.x + d[0];
            int ny = centre.y + d[1];

            if (!plateau.coordonneeValide(nx, ny)) {
                continue;
            }

            int contenu = plateau.donneContenuCelluleSansJoueur(nx, ny);
            if (Plateau.contientUneUniteDeProduction(contenu)) {
                int proprio = Plateau.donneUtilisateurDeLUniteDeProduction(contenu);
                if (proprio != monRang + 1) {
                    bonus += BONUS_ENCHAINEMENT;
                }
            }
        }

        return bonus;
    }

    // =========================================================================
    // OLIVERAIES
    // =========================================================================

    private Point trouveOliveraieStrategique(Plateau plateau, Point position, int monRang) {
        int taille = plateau.donneTaille();

        Point meilleure = null;
        double meilleurScore = Double.NEGATIVE_INFINITY;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);
                if (!Plateau.contientUneUniteDeRessourcage(contenu)) {
                    continue;
                }

                Point cible = new Point(x, y);
                int dist = distance(plateau, position, cible);

                if (dist < 0) {
                    continue;
                }

                if (dist > donneRessources() && dist > 0) {
                    continue;
                }

                double score = 400.0 - dist * 18.0;

                if (secteurDe(cible, taille) == secteurDepart) {
                    score += 180.0;
                }

                score -= risqueCollisionSurCase(plateau, cible, monRang) * 120.0;

                if (score > meilleurScore) {
                    meilleurScore = score;
                    meilleure = cible;
                }
            }
        }

        return meilleure;
    }

    // =========================================================================
    // DÉPLACEMENT
    // =========================================================================

    private Action allerVers(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null) {
            return null;
        }

        if (depart.equals(cible)) {
            return Action.RIEN;
        }

        ArrayList<Noeud> chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                depart, cible, obstaclesJoueurs(plateau));

        if (chemin == null || chemin.isEmpty()) {
            chemin = plateau.donneCheminEntre(depart, cible);
        }

        if (chemin == null || chemin.isEmpty()) {
            return null;
        }

        Noeud prochain = chemin.get(0);
        return directionVers(depart, prochain.getX(), prochain.getY());
    }

    private int distance(Plateau plateau, Point depart, Point arrivee) {
        if (plateau == null || depart == null || arrivee == null) {
            return -1;
        }

        if (depart.equals(arrivee)) {
            return 0;
        }

        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, arrivee);
        if (chemin == null) {
            return -1;
        }

        return chemin.size();
    }

    private List<Noeud> obstaclesJoueurs(Plateau plateau) {
        List<Noeud> obstacles = new ArrayList<>();

        for (Joueur j : plateau.donneJoueurs()) {
            if (j == null || j == this || j.donnePosition() == null) {
                continue;
            }
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

    private Action mouvementDeSecours(Plateau plateau, Point position, int monRang) {
        Action[] actions = {Action.DROITE, Action.BAS, Action.GAUCHE, Action.HAUT};

        for (Action a : actions) {
            Point cible = caseApresAction(position, a);

            if (cible == null || !plateau.coordonneeValide(cible.x, cible.y)) {
                continue;
            }

            int contenu = plateau.donneContenuCellule(cible.x, cible.y);
            if (Plateau.contientUneZoneInfranchissable(contenu)) {
                continue;
            }

            if (!collisionProbableApresAction(plateau, position, a, monRang)) {
                return a;
            }
        }

        return Action.RIEN;
    }

    private Point caseApresAction(Point p, Action a) {
        if (p == null || a == null) {
            return null;
        }

        if (a == Action.DROITE) return new Point(p.x + 1, p.y);
        if (a == Action.GAUCHE) return new Point(p.x - 1, p.y);
        if (a == Action.BAS) return new Point(p.x, p.y + 1);
        if (a == Action.HAUT) return new Point(p.x, p.y - 1);

        return new Point(p.x, p.y);
    }

    // =========================================================================
    // RISQUES / CONTESTATION
    // =========================================================================

    private boolean collisionProbableApresAction(Plateau plateau, Point position, Action action, int monRang) {
        Point cible = caseApresAction(position, action);

        if (cible == null || !plateau.coordonneeValide(cible.x, cible.y)) {
            return true;
        }

        Joueur surCase = plateau.donneJoueurEnPosition(cible);
        if (surCase != null && surCase.donneRang() != monRang) {
            return true;
        }

        for (Joueur j : plateau.donneJoueurs()) {
            if (j == null || j.donneRang() == monRang || j.donnePosition() == null) {
                continue;
            }

            int manhattan = Math.abs(j.donnePosition().x - cible.x) + Math.abs(j.donnePosition().y - cible.y);
            if (manhattan <= 1) {
                return true;
            }
        }

        return false;
    }

    private double risqueContestation(Plateau plateau, Point cible, int monRang, int maDistance) {
        double penalite = 0.0;

        for (Joueur j : plateau.donneJoueurs()) {
            if (j == null || j.donneRang() == monRang || j.donnePosition() == null) {
                continue;
            }

            int distAdv = distance(plateau, j.donnePosition(), cible);
            if (distAdv == -1) {
                continue;
            }

            if (distAdv < maDistance) {
                penalite += 2.0;
            } else if (distAdv == maDistance) {
                penalite += 1.2;
            } else if (distAdv == maDistance + 1) {
                penalite += 0.5;
            }
        }

        return penalite;
    }

    private boolean adversairePlusProche(Plateau plateau, Point cible, int monRang, int maDistance) {
        for (Joueur j : plateau.donneJoueurs()) {
            if (j == null || j.donneRang() == monRang || j.donnePosition() == null) {
                continue;
            }

            int distAdv = distance(plateau, j.donnePosition(), cible);
            if (distAdv != -1 && distAdv < maDistance) {
                return true;
            }
        }

        return false;
    }

    private double risqueCollisionSurCase(Plateau plateau, Point cible, int monRang) {
        double penalite = 0.0;

        for (Joueur j : plateau.donneJoueurs()) {
            if (j == null || j.donneRang() == monRang || j.donnePosition() == null) {
                continue;
            }

            int dist = distance(plateau, j.donnePosition(), cible);
            if (dist == 0) {
                penalite += 2.5;
            } else if (dist == 1) {
                penalite += 1.0;
            }
        }

        return penalite;
    }

    // =========================================================================
    // OUTILS DE ZONE
    // =========================================================================

    private int secteurDe(Point pt, int taille) {
        int milieu = taille / 2;

        if (pt.x < milieu && pt.y < milieu) return 0;
        if (pt.x >= milieu && pt.y < milieu) return 1;
        if (pt.x < milieu) return 2;
        return 3;
    }

    private int compterMesMoulinsDansSecteur(Plateau plateau, int monRang, int secteur) {
        int taille = plateau.donneTaille();
        int count = 0;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);

                if (Plateau.contientUneUniteDeProduction(contenu)) {
                    int proprio = Plateau.donneUtilisateurDeLUniteDeProduction(contenu);
                    if (proprio == monRang + 1) {
                        if (secteurDe(new Point(x, y), taille) == secteur) {
                            count++;
                        }
                    }
                }
            }
        }

        return count;
    }

    private boolean estVoisinDeMesMoulins(Plateau plateau, Point cible, int monRang) {
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] d : dirs) {
            int nx = cible.x + d[0];
            int ny = cible.y + d[1];

            if (!plateau.coordonneeValide(nx, ny)) {
                continue;
            }

            int contenu = plateau.donneContenuCelluleSansJoueur(nx, ny);
            if (Plateau.contientUneUniteDeProduction(contenu)) {
                int proprio = Plateau.donneUtilisateurDeLUniteDeProduction(contenu);
                if (proprio == monRang + 1) {
                    return true;
                }
            }
        }

        return false;
    }

    private int rangLeader(Plateau plateau, int monRang) {
        int leader = -1;
        int max = -1;

        for (int r = 0; r < plateau.donneJoueurs().length; r++) {
            if (r == monRang) {
                continue;
            }

            int nb = plateau.nombreDUnitesDeProductionJoueur(r);
            if (nb > max) {
                max = nb;
                leader = r;
            }
        }

        return leader;
    }

    // =========================================================================
    // BLOCAGE
    // =========================================================================

    private void mettreAJourBlocage(Point position) {
        if (position == null) {
            toursBloque = 0;
            dernierePosition = null;
            return;
        }

        if (dernierePosition != null && dernierePosition.equals(position)) {
            toursBloque++;
        } else {
            toursBloque = 0;
        }

        dernierePosition = new Point(position.x, position.y);
    }
}