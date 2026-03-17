package main;

import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * IA Agressive : Maximise la capture de moulins (surtout ceux des autres),
 * frôle les adversaires sans déclencher de Manille, et optimise son énergie à flux tendu.
 */
public class MonJoueurAggressif extends Joueur {

    public MonJoueurAggressif(String sonNom) {
        super(sonNom);
    }

    @Override
    public Action faitUneAction(Plateau plateau) {
        Point maPosition = this.donnePosition();
        int monEnergie = this.donneRessources();
        int maCaseCourante = plateau.donneContenuCellule(maPosition);

        // --- 1. GESTION DU REPOS ---
        // Si on est dans une oliveraie, on encaisse jusqu'à être quasiment plein.
        if (Plateau.contientUneUniteDeRessourcage(maCaseCourante) && monEnergie < 90) {
            return Action.RIEN;
        }

        // --- 2. GESTION DE L'ÉNERGIE (Flux Tendu) ---
        Point oliveraieProche = trouverOliveraieLaPlusProche(plateau, maPosition);
        int distanceOliveraie = (oliveraieProche != null) ? calculerDistance(maPosition, oliveraieProche) : 0;

        // On va se recharger uniquement si on a juste assez d'énergie pour le trajet + une petite marge (10)
        boolean urgenceEnergie = monEnergie <= (distanceOliveraie + 10);

        int rechercheCible = urgenceEnergie ? Plateau.CHERCHE_RESSOURCE : Plateau.CHERCHE_PRODUCTION;

        // --- 3. SCAN RADAR ---
        HashMap<Integer, ArrayList<Point>> radar = plateau.cherche(maPosition, 40, rechercheCible);
        ArrayList<Point> ciblesPotentielles = radar.get(rechercheCible);

        if (ciblesPotentielles == null || ciblesPotentielles.isEmpty()) {
            return Action.RIEN;
        }

        // --- 4. ÉVITEMENT CHIRURGICAL (Anti-Manille) ---
        // On bloque uniquement la case de l'adversaire et ses 8 cases directement adjacentes
        List<Noeud> obstaclesAntiManille = genererBlocageChirurgical(plateau, maPosition);

        ArrayList<Noeud> meilleurChemin = null;
        double meilleurScore = -1.0;

        for (Point cible : ciblesPotentielles) {
            double scoreCible = 0;

            if (rechercheCible == Plateau.CHERCHE_PRODUCTION) {
                int contenuCase = plateau.donneContenuCellule(cible);

                // On ignore nos propres moulins
                if (!Plateau.contientUneUniteDeProductionLibre(contenuCase) &&
                        !Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenuCase)) {
                    continue;
                }

                // CALCUL DU SCORE AGRESSIF
                int distance = calculerDistance(maPosition, cible);
                if (distance == 0) distance = 1;

                scoreCible = 1000.0 / distance; // Plus c'est proche, mieux c'est

                // BONUS DE VOL : Si le moulin appartient à un ennemi, c'est une cible prioritaire !
                if (Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenuCase)) {
                    scoreCible *= 1.5; // +50% d'attractivité
                }
            } else {
                // Si on cherche une oliveraie, on prend juste la plus proche
                int distance = calculerDistance(maPosition, cible);
                scoreCible = 1000.0 / (distance == 0 ? 1 : distance);
            }

            // Calcul du vrai chemin en évitant la Manille
            ArrayList<Noeud> cheminTest = plateau.donneCheminAvecObstaclesSupplementaires(maPosition, cible, obstaclesAntiManille);

            if (cheminTest != null && !cheminTest.isEmpty() && scoreCible > meilleurScore) {
                meilleurScore = scoreCible;
                meilleurChemin = cheminTest;
            }
        }

        // --- 5. PLAN B (Patience Tactique) ---
        // Si le chemin parfait est bloqué par la zone de quarantaine d'un joueur,
        // au lieu de traverser et de déclencher une Manille, on passe notre tour (Action.RIEN).
        // L'adversaire va probablement bouger au tour suivant et libérer le passage.
        if (meilleurChemin == null && rechercheCible == Plateau.CHERCHE_PRODUCTION) {
            return Action.RIEN;
        }

        // Si urgence énergie absolue et passage bloqué, on recalcule sans la quarantaine (instinct de survie)
        if (meilleurChemin == null && rechercheCible == Plateau.CHERCHE_RESSOURCE) {
            int distMin = Integer.MAX_VALUE;
            for (Point cible : ciblesPotentielles) {
                ArrayList<Noeud> cheminTest = plateau.donneCheminEntre(maPosition, cible);
                if (cheminTest != null && !cheminTest.isEmpty() && cheminTest.size() < distMin) {
                    distMin = cheminTest.size();
                    meilleurChemin = cheminTest;
                }
            }
        }

        // --- 6. EXÉCUTION DU MOUVEMENT ---
        if (meilleurChemin != null && !meilleurChemin.isEmpty()) {
            Noeud noeudSuivant = null;
            for (Noeud n : meilleurChemin) {
                if (n.getX() != maPosition.x || n.getY() != maPosition.y) {
                    noeudSuivant = n;
                    break;
                }
            }
            if (noeudSuivant != null) {
                return determinerActionVers(maPosition, noeudSuivant);
            }
        }

        return Action.RIEN;
    }

    // =========================================================
    //         MÉTHODES UTILITAIRES
    // =========================================================

    /**
     * Crée un bouclier de 3x3 cases autour de chaque adversaire pour éviter
     * toute case adjacente (qui déclencherait une Manille).
     */
    private List<Noeud> genererBlocageChirurgical(Plateau plateau, Point maPosition) {
        List<Noeud> obstacles = new ArrayList<>();
        Joueur[] tousLesJoueurs = plateau.donneJoueurs();

        for (Joueur j : tousLesJoueurs) {
            if (j != null && j.donneRang() != this.donneRang()) {
                Point posAdversaire = j.donnePosition();

                // On bloque un carré de 3x3 autour de l'ennemi
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int zoneX = posAdversaire.x + dx;
                        int zoneY = posAdversaire.y + dy;

                        if (plateau.coordonneeValide(zoneX, zoneY) &&
                                (zoneX != maPosition.x || zoneY != maPosition.y)) {
                            obstacles.add(new Noeud(zoneX, zoneY));
                        }
                    }
                }
            }
        }
        return obstacles;
    }

    private int calculerDistance(Point a, Point b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    private Point trouverOliveraieLaPlusProche(Plateau plateau, Point maPosition) {
        HashMap<Integer, ArrayList<Point>> radar = plateau.cherche(maPosition, 40, Plateau.CHERCHE_RESSOURCE);
        ArrayList<Point> oliveraies = radar.get(Plateau.CHERCHE_RESSOURCE);

        if (oliveraies == null || oliveraies.isEmpty()) return null;

        Point plusProche = null;
        int distanceMin = Integer.MAX_VALUE;

        for (Point o : oliveraies) {
            int d = calculerDistance(maPosition, o);
            if (d < distanceMin) {
                distanceMin = d;
                plusProche = o;
            }
        }
        return plusProche;
    }

    private Action determinerActionVers(Point depart, Noeud suivant) {
        int nextX = suivant.getX();
        int nextY = suivant.getY();

        if (nextX > depart.x) return Action.DROITE;
        if (nextX < depart.x) return Action.GAUCHE;
        if (nextY > depart.y) return Action.BAS;
        if (nextY < depart.y) return Action.HAUT;

        return Action.RIEN;
    }
}