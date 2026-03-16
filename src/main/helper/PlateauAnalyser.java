package helper;

import jeu.Joueur;
import jeu.Plateau;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;


public class PlateauAnalyser {

    public int nombreRessources          = 0;
    public int nombreMoulinsLibres       = 0;
    public int nombreMoulinsAdverses     = 0;
    public int nombreMoulinsNous         = 0;
    public int nombreObstacles           = 0;
    public int totalMoulins              = 0;

    /** Toutes les oliveraies de la carte. */
    public final List<Point> oliveraies         = new ArrayList<>();
    /** Moulins libres (non capturés). */
    public final List<Point> moulinsLibres       = new ArrayList<>();
    /** Moulins appartenant à un adversaire. */
    public final List<Point> moulinsAdverses     = new ArrayList<>();
    /** Moulins nous appartenant. */
    public final List<Point> moulinsNous         = new ArrayList<>();
    /** Positions actuelles des adversaires. */
    public final List<Point> positionsAdversaires = new ArrayList<>();

    // ── Adversaire le plus riche en moulins ───────────────────────────────────

    public Joueur adversaireLePlusRiche  = null;
    public int    moulinsAdversaireLePlusRiche = 0;

    // ─────────────────────────────────────────────────────────────────────────

    public void analysePlateau(Plateau plateau, Joueur joueur) {
        reinitialiser();

        int taille = plateau.donneTaille();

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                // donneContenuCelluleSansJoueur : vrai type de case sans masque joueur
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);
                Point p = new Point(x, y);

                if (Plateau.contientUneUniteDeRessourcage(contenu)) {
                    nombreRessources++;
                    oliveraies.add(p);
                }

                if (Plateau.contientUneUniteDeProduction(contenu)) {
                    totalMoulins++;

                    if (Plateau.contientUneUniteDeProductionLibre(contenu)) {
                        nombreMoulinsLibres++;
                        moulinsLibres.add(p);

                    } else if (Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(joueur, contenu)) {
                        nombreMoulinsAdverses++;
                        moulinsAdverses.add(p);

                    } else {
                        nombreMoulinsNous++;
                        moulinsNous.add(p);
                    }
                }

                if (Plateau.contientUneZoneInfranchissable(contenu)) {
                    nombreObstacles++;
                }
            }
        }

        // Positions et stats des adversaires
        for (Joueur j : plateau.donneJoueurs()) {
            if (j == joueur) {
                continue;
            }
            positionsAdversaires.add(j.donnePosition());

            int moulinsJ = plateau.nombreDUnitesDeProductionJoueur(j.donneRang());
            if (moulinsJ > moulinsAdversaireLePlusRiche) {
                moulinsAdversaireLePlusRiche = moulinsJ;
                adversaireLePlusRiche        = j;
            }
        }
    }

    private void reinitialiser() {
        nombreRessources               = 0;
        nombreMoulinsLibres            = 0;
        nombreMoulinsAdverses          = 0;
        nombreMoulinsNous              = 0;
        nombreObstacles                = 0;
        totalMoulins                   = 0;
        moulinsAdversaireLePlusRiche   = 0;
        adversaireLePlusRiche          = null;

        oliveraies.clear();
        moulinsLibres.clear();
        moulinsAdverses.clear();
        moulinsNous.clear();
        positionsAdversaires.clear();
    }
}