import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;

public class JoueurManssour extends Joueur {

    // Énergie en dessous de laquelle on fuit quoi qu'il arrive
    private static final int ENERGIE_CRITIQUE = 15;
    // Énergie minimum pour risquer une capture (coût = 20 + dist)
    private static final int ENERGIE_MIN_CAPTURE = 25;
    // Nombre de moulins cible avant d'aller voler
    private static final int MOULINS_AVANT_VOL = 1;

    public JoueurManssour(String sonNom) {
        super(sonNom);
    }

    @Override
    public Action faitUneAction(Plateau p) {
        try {
            return choisirAction(p);
        } catch (Exception e) {
            return Action.RIEN;
        }
    }

    // ─── DÉCISION CENTRALE ─────────────────────────────────────────

    private Action choisirAction(Plateau p) {
        Point maPos       = this.donnePosition();
        int   monRang     = this.donneRang();
        int   energie     = this.donneRessources();
        int   toursRest   = p.donneNombreDeTours() - p.donneTourCourant();
        int   mesMoulins  = p.nombreDUnitesDeProductionJoueur(monRang);

        // Fuite d'urgence — toujours prioritaire
        if (energie <= ENERGIE_CRITIQUE) {
            Point ol = plusProche(p, maPos, Plateau.CHERCHE_RESSOURCE, -1, monRang);
            return actionVers(p, maPos, ol);
        }

        // Évaluation de chaque option avec la vraie formule ROI
        double bestScore = Double.NEGATIVE_INFINITY;
        Action bestAction = Action.RIEN;

        // ── Option A : capturer un moulin libre ──────────────────────
        if (energie >= ENERGIE_MIN_CAPTURE) {
            Candidat ca = evaluerCapture(p, maPos, monRang, energie, toursRest, false);
            if (ca != null && ca.score > bestScore) {
                bestScore = ca.score;
                bestAction = actionVers(p, maPos, ca.cible);
            }
        }

        // ── Option B : voler un moulin adverse ───────────────────────
        int leaderRang    = rangLeader(p, monRang);
        int leaderMoulins = leaderRang >= 0 ? p.nombreDUnitesDeProductionJoueur(leaderRang) : 0;
        boolean jeSuisLeLeader = mesMoulins > leaderMoulins;

        if (energie >= ENERGIE_MIN_CAPTURE && mesMoulins >= MOULINS_AVANT_VOL && !jeSuisLeLeader) {            Candidat cv = evaluerCapture(p, maPos, monRang, energie, toursRest, true);
            if (cv != null && cv.score > bestScore) {
                bestScore = cv.score;
                bestAction = actionVers(p, maPos, cv.cible);
            }
        }

        // ── Option C : manille (RIEN) ─────────────────────────────────
        double scoreManille = evaluerManille(p, maPos, monRang, energie, mesMoulins, toursRest);
        if (scoreManille > bestScore) {
            bestScore = scoreManille;
            bestAction = Action.RIEN;
        }

        // ── Option D : recharge oliveraie ─────────────────────────────
        double scoreOliv = evaluerOliveraie(p, maPos, energie, mesMoulins, toursRest);
        Point oliv = plusProche(p, maPos, Plateau.CHERCHE_RESSOURCE, -1, monRang);
        if (scoreOliv > bestScore && oliv != null) {
            bestScore = scoreOliv;
            bestAction = actionVers(p, maPos, oliv);
        }

        return bestAction;
    }

    // ─── FORMULES ROI ──────────────────────────────────────────────

    /**
     * ROI d'une capture (libre ou vol adverse).
     *
     * ROI_libre = toursRestants - coûtTotal
     * ROI_vol   = 2 × toursRestants - coûtTotal   (on enlève ET on gagne)
     *
     * coûtTotal = dist (déplacement) + 20 (capture) + dist × (mesMoulins × 0.5)
     * Le dernier terme = bouteilles perdues pendant le trajet (production non perçue).
     */
    private Candidat evaluerCapture(Plateau p, Point maPos, int monRang,
                                    int energie, int toursRest, boolean volAdverse) {
        HashMap<Integer, ArrayList<Point>> res =
                p.cherche(maPos, p.donneTaille(), Plateau.CHERCHE_PRODUCTION);
        ArrayList<Point> moulins = res.get(Plateau.CHERCHE_PRODUCTION);
        if (moulins == null) return null;

        int mesMoulins = p.nombreDUnitesDeProductionJoueur(monRang);
        Candidat meilleur = null;

        for (Point moulin : moulins) {
            int contenu = p.donneContenuCellule(moulin.x, moulin.y);
            int proprio = Plateau.donneUtilisateurDeLUniteDeProduction(contenu);

            boolean estLibre  = (proprio == 0);
            boolean estAdvers = (proprio != 0 && proprio != monRang + 1);

            if (volAdverse && !estAdvers) continue;
            if (!volAdverse && !estLibre)  continue;

            int dist = distanceChemin(p, maPos, moulin);
            if (dist <= 0) continue;

            // Coût total : déplacement + capture + opportunité production
            int coutTotal = dist + 20 + (int)(dist * mesMoulins * 0.5);

            // On ne capture que si l'énergie suffit
            if (energie < 20 + dist) continue;
            // Seuil de rentabilité mathématique (pas de constante arbitraire)
            double gainBrut = volAdverse ? 2.0 * toursRest : (double) toursRest;
            double roi = gainBrut - coutTotal;

            if (roi <= 0) continue; // pas rentable

            // Bonus si l'adversaire domine (priorité stratégique)
            if (volAdverse) {
                int monNbMoulins = p.nombreDUnitesDeProductionJoueur(monRang);
                int moulinsAdv   = p.nombreDUnitesDeProductionJoueur(proprio - 1);
                int delta        = moulinsAdv - monNbMoulins;

                if (delta >= 2) roi *= 2.0;
                else if (delta == 1) roi *= 1.5;

                int leader = rangLeader(p, monRang);
                if (proprio - 1 == leader) roi += 200.0;
            }

            if (meilleur == null || roi > meilleur.score) {
                meilleur = new Candidat(moulin, roi);
            }
        }
        return meilleur;
    }

    /**
     * ROI de la manille.
     *
     * La manille donne +2 énergie × 10 tours = +20 énergie.
     * Valeur en bouteilles = 20 énergie × (coût_déplacement / 100)
     * soit ce que ça nous économise de ne pas aller en oliveraie.
     *
     * Coût d'opportunité = 10 tours perdus × mesMoulins bouteilles/tour.
     */
    private double evaluerManille(Plateau p, Point maPos, int monRang,
                                  int energie, int mesMoulins, int toursRest) {
        if (!voisinEligible(p, maPos, monRang)) return Double.NEGATIVE_INFINITY;

        // Valeur de l'énergie récupérée : économie d'un aller en oliveraie
        Point oliv = plusProche(p, maPos, Plateau.CHERCHE_RESSOURCE, -1, monRang);
        double distOliv = oliv != null ? distanceChemin(p, maPos, oliv) : 10.0;

        double gainEnergie = 20.0; // +2/tour × 10 tours
        // Ce gain "vaut" d'autant plus qu'on est loin de l'oliveraie
        double valeurEnergie = gainEnergie * (distOliv / 10.0);
        // Coût d'opportunité : 10 tours × moulins bouteilles/tour perdus
        double coutOppor = 10.0 * mesMoulins;

        return valeurEnergie - coutOppor;
    }

    /**
     * ROI d'aller en oliveraie.
     *
     * Gain = énergie récupérée estimée (moyenne : ~30)
     * Coût = dist + (dist × mesMoulins) [bouteilles perdues pendant trajet]
     *
     * L'oliveraie devient intéressante quand l'énergie manque pour capturer,
     * ou quand le trajet est court et qu'on a peu de moulins.
     */
    private double evaluerOliveraie(Plateau p, Point maPos,
                                    int energie, int mesMoulins, int toursRest) {
        Point oliv = plusProche(p, maPos, Plateau.CHERCHE_RESSOURCE, -1, -1);
        if (oliv == null) return Double.NEGATIVE_INFINITY;

        int dist = distanceChemin(p, maPos, oliv);
        if (dist <= 0) return Double.NEGATIVE_INFINITY;

        if (energie >= 40) return Double.NEGATIVE_INFINITY;

        double gainEstime = Math.min((100.0 - energie) * 0.7, 40.0);
        double coutTotal  = dist + dist * mesMoulins;

        return gainEstime - coutTotal;
    }

    // ─── UTILITAIRES ──────────────────────────────────────────────
    private int rangLeader(Plateau p, int monRang) {
        int maxMoulins = 0, rangMax = -1;
        for (int r = 0; r < 4; r++) {
            if (r == monRang) continue;
            int m = p.nombreDUnitesDeProductionJoueur(r);
            if (m > maxMoulins) { maxMoulins = m; rangMax = r; }
        }
        return rangMax;
    }

    private boolean voisinEligible(Plateau p, Point maPos, int monRang) {
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
        for (int[] d : dirs) {
            Point v = new Point(maPos.x + d[0], maPos.y + d[1]);
            if (!p.coordonneeValide(v.x, v.y)) continue;
            Joueur j = p.donneJoueurEnPosition(v);
            if (j != null && j.donneRang() != monRang
                    && p.donneToursRestantEchange()[j.donneRang()] == 0
                    && !Plateau.contientUneUniteDeRessourcage(p.donneContenuCellule(v))) {
                return true;
            }
        }
        return false;
    }

    private Point plusProche(Plateau p, Point maPos, int type,
                             int filtreProprio, int monRang) {
        HashMap<Integer, ArrayList<Point>> res = p.cherche(maPos, p.donneTaille(), type);
        ArrayList<Point> pts = res.get(type);
        if (pts == null || pts.isEmpty()) return null;

        Point best = null;
        int distMin = Integer.MAX_VALUE;

        for (Point pt : pts) {
            if (type == Plateau.CHERCHE_PRODUCTION) {
                int contenu = p.donneContenuCellule(pt.x, pt.y);
                int proprio = Plateau.donneUtilisateurDeLUniteDeProduction(contenu);
                if (monRang >= 0 && proprio == monRang + 1) continue;
                if (filtreProprio == 0 && proprio != 0) continue;
            }
            int dist = distanceChemin(p, maPos, pt);
            if (dist > 0 && dist < distMin) {
                distMin = dist;
                best = pt;
            }
        }
        return best;
    }

    private int distanceChemin(Plateau p, Point a, Point b) {
        if (b == null) return -1;
        ArrayList<Noeud> ch = p.donneCheminEntre(a, b);
        return ch == null ? -1 : ch.size();
    }

    private Action actionVers(Plateau p, Point maPos, Point cible) {
        if (cible == null) return Action.RIEN;
        ArrayList<Noeud> ch = p.donneCheminEntre(maPos, cible);
        if (ch == null || ch.isEmpty()) return Action.RIEN;
        Noeud n = ch.get(0);
        int dx = n.getX() - maPos.x;
        int dy = n.getY() - maPos.y;
        if (dx ==  1) return Action.DROITE;
        if (dx == -1) return Action.GAUCHE;
        if (dy ==  1) return Action.BAS;
        if (dy == -1) return Action.HAUT;
        return Action.RIEN;
    }

    private static class Candidat {
        final Point  cible;
        final double score;
        Candidat(Point p, double s) { cible = p; score = s; }
    }
}