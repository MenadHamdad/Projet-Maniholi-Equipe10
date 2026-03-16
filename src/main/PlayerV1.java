import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.*;
import java.util.ArrayList;

public class PlayerV1 extends Joueur {

    PlateauAnalyser plateauAnalyser;

    public PlayerV1(String sonNom) {
        super(sonNom);
        this.plateauAnalyser = new PlateauAnalyser();
    }

    @Override
    public Action faitUneAction(Plateau plateau) {

        plateauAnalyser.analysePlateau(plateau, this);

        Point position = donnePosition();
        int ressources = donneRessources();

        Point ressourceLaPlusProche = trouveRessourceLaPlusProche(plateau, position);
        Point productionLaPlusProche = trouveProductionInteressanteLaPlusProche(plateau, this);

        int distanceRessource = distance(plateau, position, ressourceLaPlusProche);
        int distanceProduction = distance(plateau, position, productionLaPlusProche);

        return Action.RIEN;
    }

    private Point trouveProductionInteressanteLaPlusProche(Plateau plateau, Joueur moi) {
        Point positionDepart = moi.donnePosition();
        Point meilleureProduction = null;
        int meilleureDistance = -1;
        int taille = plateau.donneTaille();

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCellule(x, y);

                boolean productionLibre = Plateau.contientUneUniteDeProductionLibre(contenu);
                boolean productionAdverse = Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(moi, contenu);

                if (productionLibre || productionAdverse) {
                    Point cible = new Point(x, y);
                    int distanceCourante = distance(plateau, positionDepart, cible);

                    if (distanceCourante != -1) {
                        if (meilleureDistance == -1 || distanceCourante < meilleureDistance) {
                            meilleureDistance = distanceCourante;
                            meilleureProduction = cible;
                        }
                    }
                }
            }
        }

        return meilleureProduction;
    }

    private Point trouveRessourceLaPlusProche(Plateau plateau, Point positionDepart) {
        Point meilleureRessource = null;
        int meilleureDistance = -1;
        int taille = plateau.donneTaille();

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCellule(x, y);

                if (Plateau.contientUneUniteDeRessourcage(contenu)) {
                    Point cible = new Point(x, y);
                    int distanceCourante = distance(plateau, positionDepart, cible);

                    if (distanceCourante != -1) {
                        if (meilleureDistance == -1 || distanceCourante < meilleureDistance) {
                            meilleureDistance = distanceCourante;
                            meilleureRessource = cible;
                        }
                    }
                }
            }
        }

        return meilleureRessource;
    }

    private int distance(Plateau plateau, Point depart, Point arrivee) {
        if (plateau == null || depart == null || arrivee == null) {
            return -1;
        }

        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, arrivee);

        if (chemin == null || chemin.isEmpty()) {
            return -1;
        }

        return chemin.size() - 1;
    }
}

