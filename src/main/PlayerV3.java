import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

public class PlayerV3 extends Joueur {

    private enum Etat {
        CHERCHE_MOULIN,
        VA_RECHARGER,
        EN_RECHARGE
    }

    private static final int SEUIL_ENERGIE_CRITIQUE  = 35;
    private static final int COUT_CAPTURE            = 20;
    private static final int MARGE_SECURITE          = 15;

    private static final int    RAYON_CLUSTER = 5;
    private static final double POIDS_CLUSTER = 2.5;
    private static final double BONUS_ENNEMI  = 1.4;

    private final PlateauAnalyser plateauAnalyser;

    private Etat  etatCourant;
    private Point cibleCourante;

    public PlayerV3(String sonNom) {
        super(sonNom);
        this.plateauAnalyser = new PlateauAnalyser();
        resetMemoire();
    }

    @Override
    public void debutDePartie(int rang) {
        resetMemoire();
    }

    // Ajout de la méthode de fin de partie demandée par les règles
    public void finDePartie(Plateau plateau) {
        resetMemoire();
    }

    private void resetMemoire() {
        this.etatCourant = Etat.CHERCHE_MOULIN;
        this.cibleCourante = null;
    }

    @Override
    public Action faitUneAction(Plateau plateau) {
        plateauAnalyser.analysePlateau(plateau, this);

        final Point position = donnePosition();
        final int   energie  = donneRessources();

        // Sécurité anti-crash au démarrage
        if (position == null) return Action.RIEN;

        // 1. RECHARGE COMPLÈTE SUR OLIVERAIE
        if (estSurOliveraie(plateau, position)) {
            if (energie < 100) {
                etatCourant = Etat.EN_RECHARGE;
                cibleCourante = null;
                return Action.RIEN;
            } else {
                etatCourant = Etat.CHERCHE_MOULIN;
            }
        }

        // 2. URGENCE ÉNERGÉTIQUE
        if (energie <= SEUIL_ENERGIE_CRITIQUE && etatCourant != Etat.VA_RECHARGER) {
            etatCourant = Etat.VA_RECHARGER;
            cibleCourante = trouveOliveraieProche(plateau, position);
        }

        // 3. EXÉCUTION DE L'ÉTAT
        switch (etatCourant) {
            case VA_RECHARGER:
                if (cibleCourante == null || position.equals(cibleCourante)) {
                    cibleCourante = trouveOliveraieProche(plateau, position);
                }
                if (cibleCourante != null) {
                    Action a = allerVers(plateau, position, cibleCourante);
                    if (a != null) return a;
                }
                break;

            case CHERCHE_MOULIN:
                if (cibleCourante == null || !estMoulinValide(plateau, cibleCourante)) {
                    cibleCourante = trouveMeilleurMoulin(plateau, position, energie);
                }
                if (cibleCourante != null) {
                    Action a = allerVers(plateau, position, cibleCourante);
                    if (a != null) return a;
                }
                break;

            case EN_RECHARGE:
                return Action.RIEN;
        }

        cibleCourante = null;
        return Action.RIEN;
    }

    // =========================================================================
    //  NAVIGATION
    // =========================================================================

    private Action allerVers(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null || depart.equals(cible)) return Action.RIEN;

        // On passe la cible à zonesDeManille pour ne pas la bloquer par erreur !
        ArrayList<Noeud> chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                depart, cible, zonesDeManille(plateau, cible));

        if (chemin == null || chemin.isEmpty()) {
            chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                    depart, cible, obstaclesJoueurs(plateau));
        }

        if (chemin == null || chemin.isEmpty()) {
            chemin = plateau.donneCheminEntre(depart, cible);
        }

        if (chemin == null || chemin.isEmpty()) return null;

        Noeud prochainNoeud = chemin.get(0);
        return directionVers(depart, prochainNoeud.getX(), prochainNoeud.getY());
    }

    // Correction des OutOfBounds et du blocage de la cible
    private List<Noeud> zonesDeManille(Plateau plateau, Point cible) {
        List<Noeud> danger = new ArrayList<>();
        int[][] directions = {{0,0}, {1,0}, {-1,0}, {0,1}, {0,-1}};

        for (Joueur j : plateau.donneJoueurs()) {
            if (j == this || j.donnePosition() == null) continue;
            Point p = j.donnePosition();

            for (int[] d : directions) {
                int nx = p.x + d[0];
                int ny = p.y + d[1];

                // On vérifie que la case existe sur le plateau
                if (plateau.coordonneeValide(nx, ny)) {
                    // On ne marque JAMAIS notre destination comme zone de danger, sinon A* plante !
                    if (cible == null || nx != cible.x || ny != cible.y) {
                        danger.add(new Noeud(nx, ny));
                    }
                }
            }
        }
        return danger;
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
    //  RECHERCHE DE MOULIN (OPTIMISÉ)
    // =========================================================================

    private class CandidatMoulin {
        Point point;
        double scoreManhattan;
        CandidatMoulin(Point p, double score) { this.point = p; this.scoreManhattan = score; }
    }

    private Point trouveMeilleurMoulin(Plateau plateau, Point position, int energie) {
        final int taille = plateau.donneTaille();
        final int toursRestants = plateau.donneNombreDeTours() - plateau.donneTourCourant();
        List<CandidatMoulin> candidats = new ArrayList<>();

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);
                boolean libre  = Plateau.contientUneUniteDeProductionLibre(contenu);
                boolean ennemi = Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu);

                if (!libre && !ennemi) continue;

                Point cible = new Point(x, y);
                if (position.equals(cible)) continue;

                int distManhattan = distanceManhattan(position, cible);
                if (distManhattan + COUT_CAPTURE > energie) continue;

                int toursGain = toursRestants - distManhattan;
                if (toursGain <= 0) continue;

                double score = toursGain;
                score += compteMoulinsProches(plateau, cible, RAYON_CLUSTER) * POIDS_CLUSTER;
                if (ennemi) score *= BONUS_ENNEMI;
                score /= (distManhattan + 1.0);

                candidats.add(new CandidatMoulin(cible, score));
            }
        }

        candidats.sort((c1, c2) -> Double.compare(c2.scoreManhattan, c1.scoreManhattan));

        for (int i = 0; i < Math.min(5, candidats.size()); i++) {
            Point cibleTest = candidats.get(i).point;
            int distReelle = distanceAStar(plateau, position, cibleTest);

            if (distReelle > 0 && distReelle + COUT_CAPTURE + MARGE_SECURITE <= energie) {
                return cibleTest;
            }
        }

        return null;
    }

    private boolean estMoulinValide(Plateau plateau, Point cible) {
        if (cible == null) return false;
        int contenu = plateau.donneContenuCelluleSansJoueur(cible.x, cible.y);
        boolean libre  = Plateau.contientUneUniteDeProductionLibre(contenu);
        boolean ennemi = Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu);
        return libre || ennemi;
    }

    // =========================================================================
    //  OLIVERAIE
    // =========================================================================

    // Suppression de la contrainte d'énergie pour toujours trouver une survie possible
    private Point trouveOliveraieProche(Plateau plateau, Point position) {
        final int taille = plateau.donneTaille();
        Point meilleure = null;
        int meilleureDist = Integer.MAX_VALUE;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);
                if (!Plateau.contientUneUniteDeRessourcage(contenu)) continue;

                Point cible = new Point(x, y);
                if (position.equals(cible)) return cible;

                int distManhattan = distanceManhattan(position, cible);
                if (distManhattan < meilleureDist) {
                    meilleureDist = distManhattan;
                    meilleure = cible;
                }
            }
        }
        return meilleure;
    }

    private boolean estSurOliveraie(Plateau plateau, Point position) {
        if (position == null) return false;
        int contenu = plateau.donneContenuCelluleSansJoueur(position.x, position.y);
        return Plateau.contientUneUniteDeRessourcage(contenu);
    }

    // =========================================================================
    //  OUTILS DE DISTANCE ET DÉPLACEMENT
    // =========================================================================

    private int distanceManhattan(Point p1, Point p2) {
        if (p1 == null || p2 == null) return Integer.MAX_VALUE;
        return Math.abs(p1.x - p2.x) + Math.abs(p1.y - p2.y);
    }

    private int distanceAStar(Plateau plateau, Point depart, Point arrivee) {
        if (depart == null || arrivee == null) return -1;
        ArrayList<Noeud> chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                depart, arrivee, zonesDeManille(plateau, arrivee));

        if (chemin == null || chemin.isEmpty()) {
            chemin = plateau.donneCheminEntre(depart, arrivee);
        }
        if (chemin == null) return -1;
        return chemin.size();
    }

    private int compteMoulinsProches(Plateau plateau, Point centre, int rayon) {
        int count = 0;
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