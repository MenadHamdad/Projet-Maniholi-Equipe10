import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.Point;
import java.util.ArrayList;

public class JoueurManssour extends Joueur {

    public JoueurManssour(String sonNom) {
        super(sonNom);
    }

    @Override
    public Action faitUneAction(Plateau plateau) {
        try {
            Point maPos      = donnePosition();
            int   monEnergie = donneRessources();
            int   tourCourant= plateau.donneTourCourant();
            int   gainActuel = determinerGainOliveraie(tourCourant);

            if (estMenaceParManille(plateau, maPos)) {
                return fuir(maPos, plateau);
            }

            Point oliveraieProche = trouveOliveraieProche(plateau, maPos);
            int   distOliveraie   = distance(plateau, maPos, oliveraieProche);

            if (distOliveraie >= 0 && monEnergie <= distOliveraie + 2) {
                return allerVers(plateau, maPos, oliveraieProche);
            }

            if (estSurOliveraie(plateau, maPos) && monEnergie < 90 && gainActuel >= 20) {
                return Action.RIEN;
            }

            Point cibleMoulin = trouveMoulinLePlusRentable(plateau, maPos, monEnergie, tourCourant, gainActuel);
            if (cibleMoulin != null) {
                return allerVers(plateau, maPos, cibleMoulin);
            }

            if (oliveraieProche != null && !maPos.equals(oliveraieProche)) {
                return allerVers(plateau, maPos, oliveraieProche);
            }

            return Action.RIEN;

        } catch (Exception e) {
            return Action.RIEN;
        }
    }

    // ─── ROI ──────────────────────────────────────────────────────

    private Point trouveMoulinLePlusRentable(Plateau plateau, Point maPos,
                                             int energie, int tourCourant, int gainActuel) {
        final int taille     = plateau.donneTaille();
        Point     meilleur   = null;
        double    meilleurRoi= -1.0;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                int contenu = plateau.donneContenuCelluleSansJoueur(x, y);

                if (!Plateau.contientUneUniteDeProductionLibre(contenu) &&
                        !Plateau.contientUneUniteDeProductionQuiNeLuiAppartientPas(this, contenu)) continue;

                Point cible = new Point(x, y);
                int   dist  = distance(plateau, maPos, cible);

                if (dist < 0) continue;
                if (energie < dist + 21) continue;

                double bouteillesEstimees = 1000.0 - tourCourant - dist;
                if (bouteillesEstimees <= 0) continue;

                double coutTemps = dist + ((double)(dist + 20) / gainActuel);
                double roi       = bouteillesEstimees / coutTemps;

                if (roi > meilleurRoi) {
                    meilleurRoi = roi;
                    meilleur    = cible;
                }
            }
        }
        return meilleur;
    }

    // ─── NAVIGATION ───────────────────────────────────────────────

    private Action allerVers(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null || depart.equals(cible)) return Action.RIEN;
        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, cible);
        if (chemin == null || chemin.isEmpty()) return Action.RIEN;
        Noeud n = chemin.get(0);
        return directionVers(depart, n.getX(), n.getY());
    }

    private int distance(Plateau plateau, Point depart, Point arrivee) {
        if (depart == null || arrivee == null) return -1;
        if (depart.equals(arrivee)) return 0;
        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, arrivee);
        return chemin == null ? -1 : chemin.size();
    }

    private Action directionVers(Point depart, int nx, int ny) {
        if (nx > depart.x) return Action.DROITE;
        if (nx < depart.x) return Action.GAUCHE;
        if (ny > depart.y) return Action.BAS;
        if (ny < depart.y) return Action.HAUT;
        return Action.RIEN;
    }

    // ─── ENVIRONNEMENT ────────────────────────────────────────────

    private boolean estMenaceParManille(Plateau plateau, Point maPos) {
        if (estSurOliveraie(plateau, maPos)) return false;

        for (Joueur adv : plateau.donneJoueurs()) {
            if (adv == null || adv == this || adv.donnePosition() == null) continue;
            int dx = Math.abs(adv.donnePosition().x - maPos.x);
            int dy = Math.abs(adv.donnePosition().y - maPos.y);
            if (dx + dy == 1) return true;
        }
        return false;
    }

    private Action fuir(Point depart, Plateau plateau) {
        int x = depart.x, y = depart.y;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        Action[] acts = {Action.DROITE, Action.GAUCHE, Action.BAS, Action.HAUT};

        for (int i = 0; i < dirs.length; i++) {
            int nx = x + dirs[i][0], ny = y + dirs[i][1];
            if (!plateau.coordonneeValide(nx, ny)) continue;
            // Case sans joueur dessus
            if (plateau.donneJoueurEnPosition(new Point(nx, ny)) != null) continue;
            return acts[i];
        }
        return Action.RIEN;
    }

    private Point trouveOliveraieProche(Plateau plateau, Point position) {
        int   taille  = plateau.donneTaille();
        Point best    = null;
        int   distMin = Integer.MAX_VALUE;

        for (int y = 0; y < taille; y++) {
            for (int x = 0; x < taille; x++) {
                if (!Plateau.contientUneUniteDeRessourcage(
                        plateau.donneContenuCelluleSansJoueur(x, y))) continue;
                Point cible = new Point(x, y);
                int   dist  = distance(plateau, position, cible);
                if (dist >= 0 && dist < distMin) {
                    distMin = dist;
                    best    = cible;
                }
            }
        }
        return best;
    }

    private boolean estSurOliveraie(Plateau plateau, Point position) {
        return Plateau.contientUneUniteDeRessourcage(
                plateau.donneContenuCelluleSansJoueur(position.x, position.y));
    }

    private int determinerGainOliveraie(int tour) {
        if (tour <= 200) return 10;
        if (tour <= 400) return 20;
        if (tour <= 600) return 60;
        if (tour <= 800) return 20;
        return 10;
    }
}