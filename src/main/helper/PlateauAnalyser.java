package helper;

import jeu.Joueur;
import jeu.Plateau;

public class PlateauAnalyser {

    int nombreRessources = 0;
    int nombreProductionsLibres = 0;
    int nombreProductionsAdverses = 0;
    int nombreObstacles = 0;


    public void analysePlateau (Plateau etatDuJeu , Joueur joueur) {

        int taille = etatDuJeu.donneTaille();

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = etatDuJeu.donneContenuCellule(x, y);

                if (Plateau.contientUneUniteDeRessourcage(contenu)) {
                    nombreRessources++;
                }

                if (Plateau.contientUneUniteDeProductionLibre(contenu)) {
                    nombreProductionsLibres++;
                }

                if (Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(joueur, contenu)) {
                    nombreProductionsAdverses++;
                }

                if (Plateau.contientUneZoneInfranchissable(contenu)) {
                    nombreObstacles++;
                }
            }
        }

    }
}
