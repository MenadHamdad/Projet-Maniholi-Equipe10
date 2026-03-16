import jeu.Joueur;
import jeu.Plateau;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Provençal Suprême — stratégie optimale pour le tournoi Manihòli 2026.
 *
 * <h2>Architecture : machine à scoring continu</h2>
 * À chaque tour, une fonction d'utilité {@code U(action)} évalue les 5 actions
 * possibles et retourne la meilleure. Pas de logique if/else imbriquée fragile :
 * tout module défaillant retourne {@code Action.RIEN} via le try/catch central.
 *
 * <pre>
 * U(action) = gain_bouteilles_actualisé
 *           - coût_énergie_pondéré
 *           - risque_vol × facteur_temps
 * </pre>
 *
 * {@code facteur_temps = tours_restants / nb_tours_max} rend les décisions
 * de fin de partie conservatrices : bouger coûte cher quand il reste peu de tours.
 *
 * <h2>Modules (priorité décroissante)</h2>
 * <ol>
 *   <li>M0 — Garde-fous : échange en cours → RIEN imposé</li>
 *   <li>M1 — Esquive : éviter une manille forcée en endgame</li>
 *   <li>M2 — Urgence énergie : recharger avant la panne sèche</li>
 *   <li>M3 — Scoring utilitaire : meilleure action selon U</li>
 * </ol>
 *
 * <h2>Terminologie API jar</h2>
 * "échange" = manille, "unité de production" = moulin,
 * "unité de ressourçage" = oliveraie, "ressources" = énergie du joueur.
 *
 * <h2>Convention donneToursRestantEchange()[rang]</h2>
 * <ul>
 *   <li>{@code > 0} : joueur en échange actif</li>
 *   <li>{@code < 0} : cooldown en cours</li>
 *   <li>{@code = 0} : joueur libre</li>
 * </ul>
 *
 * @author équipe ???
 */
public class JoueurManssour extends Joueur {

    // =========================================================================
    // Constantes de tuning
    // =========================================================================

    private static final int    SEUIL_URGENCE       = 30;
    private static final int    SEUIL_MANILLE        = 70;
    private static final int    NB_MOULINS_BASE      = 2;
    private static final double W_ENERGIE            = 0.15;
    private static final double W_RISQUE             = 1.8;
    private static final double W_ISOLATION          = 0.5;
    private static final int    SEUIL_ENDGAME        = 800;
    private static final double AGRESSIVITE_DEFAUT   = 0.5;
    private static final int    DIST_INFLUENCE_MAX   = 20;

    /** Énergie cible avant de quitter l'oliveraie. */
    private static final int    SEUIL_QUITTER_OLIVE  = 80;

    // =========================================================================
    // État interne
    // =========================================================================

    private Point[]  cacheMoulins;
    private double[] agressivite;
    private Point[]  positionsPrecedentes;
    private int      nbMoulinsCible;

    // =========================================================================
    // Constructeur
    // =========================================================================

    public JoueurManssour(String sonNom) {
        super(sonNom);
    }

    // =========================================================================
    // Cycle de vie
    // =========================================================================

    @Override
    public void debutDePartie(int rang) {
        cacheMoulins         = null;
        agressivite          = null;
        positionsPrecedentes = null;
        nbMoulinsCible       = NB_MOULINS_BASE;
    }

    @Override
    public void finDePartie(String encodagePlateau) {
        // intentionnellement vide
    }

    // =========================================================================
    // Point d'entrée principal
    // =========================================================================

    @Override
    public Action faitUneAction(Plateau p) {
        try {
            init(p);
            majAgressivite(p);

            Action a;
            if ((a = m0Gardefous(p)) != null) return a;
            if ((a = m1Esquive(p))   != null) return a;
            if ((a = m2Urgence(p))   != null) return a;
            return m3Scoring(p);

        } catch (Exception ex) {
            log("faitUneAction : " + ex.getMessage());
            return Action.RIEN;
        }
    }

    // =========================================================================
    // M0 — Garde-fous
    // =========================================================================

    /**
     * FIX PRINCIPAL — bug "0 points" corrigé ici.
     *
     * Avant : M0 retournait RIEN dès que le joueur était sur une oliveraie,
     * ce qui le bloquait là indéfiniment → enRessourcage=true tous les tours
     * → gainPointsDeProduction() jamais appelé → 0 points toute la partie.
     *
     * Maintenant : M0 ne gère QUE l'échange actif (manille).
     * L'oliveraie est gérée proprement dans M2.
     */
    private Action m0Gardefous(Plateau p) {
        // Échange actif : le moteur gère nos tours, on joue RIEN
        if (p.donneToursRestantEchange()[donneRang()] > 0) return Action.RIEN;
        return null;
    }

    // =========================================================================
    // M1 — Esquive anti-manille forcée
    // =========================================================================

    private Action m1Esquive(Plateau p) {
        try {
            int tour   = p.donneTourCourant();
            int energy = moi(p).donneRessources();
            if (tour <= SEUIL_ENDGAME && energy < 85) return null;

            Point pos      = moi(p).donnePosition();
            int[] echanges = p.donneToursRestantEchange();

            boolean menace = false;
            for (int r = 0; r < 4; r++) {
                if (r == donneRang()) continue;
                if (!libre(echanges, r)) continue;
                if (enOliveraie(r, p)) continue;
                if (distM(pos, p.donneJoueur(r).donnePosition()) == 1) {
                    menace = true;
                    break;
                }
            }
            if (!menace) return null;

            Action best     = null;
            double bestDist = distMinAdv(pos.x, pos.y, p);
            for (Action a : deplacements()) {
                Point dest = dest(pos, a);
                if (!accessibleTransit(dest, p)) continue;
                double d = distMinAdv(dest.x, dest.y, p);
                if (d > bestDist) { bestDist = d; best = a; }
            }
            return best;
        } catch (Exception ex) {
            log("m1Esquive : " + ex.getMessage());
            return null;
        }
    }

    // =========================================================================
    // M2 — Urgence énergie + gestion oliveraie
    // =========================================================================

    /**
     * Deux rôles :
     * 1. Si on est sur une oliveraie et qu'on n'a pas encore assez d'énergie → RIEN
     *    (on reste recharger jusqu'à SEUIL_QUITTER_OLIVE).
     * 2. Si l'énergie est critique (≤ SEUIL_URGENCE) → aller recharger.
     */
    private Action m2Urgence(Plateau p) {
        try {
            int   energy   = moi(p).donneRessources();
            Point pos      = moi(p).donnePosition();
            int[] echanges = p.donneToursRestantEchange();

            // Sur une oliveraie et pas encore assez rechargé → on reste
            int cellPos = p.donneContenuCellule(pos.x, pos.y);
            if (Plateau.contientUneUniteDeRessourcage(cellPos) && energy < SEUIL_QUITTER_OLIVE) {
                return Action.RIEN;
            }

            // Énergie pas critique → on laisse M3 décider
            if (energy > SEUIL_URGENCE) return null;

            // Énergie critique : manille adjacent d'abord
            if (energy > 0 && libre(echanges, donneRang())) {
                Action m = manilleAdj(pos, p, echanges);
                if (m != null) return m;
            }

            // Puis BFS vers oliveraie libre
            Point olive = oliveraieProche(pos, p);
            if (olive != null) return bfs(pos, olive, p);

            // Puis BFS vers un adversaire manillable
            if (energy > 0 && libre(echanges, donneRang())) {
                Point cible = caseManillable(pos, p, echanges);
                if (cible != null) return bfs(pos, cible, p);
            }
            return null;
        } catch (Exception ex) {
            log("m2Urgence : " + ex.getMessage());
            return null;
        }
    }

    // =========================================================================
    // M3 — Scoring par fonction d'utilité
    // =========================================================================

    private Action m3Scoring(Plateau p) {
        try {
            int    toursRest = p.donneNombreDeTours() - p.donneTourCourant();
            double ft        = (double) toursRest / p.donneNombreDeTours();
            Point  pos       = moi(p).donnePosition();
            int    energy    = moi(p).donneRessources();
            int    moulins   = p.nombreDUnitesDeProductionJoueur(donneRang());
            int[]  echanges  = p.donneToursRestantEchange();

            Action bestAction = Action.RIEN;
            double bestScore  = uRien(energy, moulins);

            for (Action a : deplacements()) {
                double u = u(dest(pos, a), p, toursRest, ft, moulins, energy);
                if (u > bestScore) { bestScore = u; bestAction = a; }
            }

            if (energy < SEUIL_MANILLE && libre(echanges, donneRang())) {
                Action m = manilleAdj(pos, p, echanges);
                if (m != null) {
                    double um = uManille(moulins, ft);
                    if (um > bestScore) { bestScore = um; bestAction = m; }
                }
            }
            return bestAction;
        } catch (Exception ex) {
            log("m3Scoring : " + ex.getMessage());
            return Action.RIEN;
        }
    }

    // =========================================================================
    // Fonction d'utilité U
    // =========================================================================

    /**
     * FIX : moulins traités AVANT contientUneZoneInfranchissable,
     * car cette méthode retourne true pour les moulins (ils sont techniquement
     * infranchissables en transit, mais capturables en destination).
     */
    private double u(Point dest, Plateau p,
                     int toursRest, double ft, int moulins, int energy) {

        if (!p.coordonneeValide(dest.x, dest.y))
            return -100 - 10 * W_ENERGIE;

        int cell = p.donneContenuCellule(dest.x, dest.y);

        // Moulins EN PREMIER
        if (Plateau.contientUneUniteDeProduction(cell)) {
            if (Plateau.donneUtilisateurDeLUniteDeProduction(cell) == donneRang())
                return -20 * W_ENERGIE;   // notre moulin, re-prise inutile
            for (int r = 0; r < 4; r++)
                if (r != donneRang() && Plateau.contientLeJoueur(cell, r))
                    return -100 - 10 * W_ENERGIE;   // adversaire dessus
            double gain   = toursRest * ft;
            double risque = risque(dest, p);
            double bonus  = moulins < nbMoulinsCible ? 60.0 * ft : 0.0;
            return gain + bonus - 20 * W_ENERGIE
                    - risque * W_RISQUE * toursRest * ft * 0.3;
        }

        // Rocher pur
        if (cell == Plateau.ENDROIT_INFRANCHISSABLE)
            return -100 - 10 * W_ENERGIE;

        // Adversaire sur case vide/départ/oliveraie
        for (int r = 0; r < 4; r++)
            if (r != donneRang() && Plateau.contientLeJoueur(cell, r))
                return -100 - 10 * W_ENERGIE;

        // Oliveraie
        if (Plateau.contientUneUniteDeRessourcage(cell)) {
            if (energy >= SEUIL_QUITTER_OLIVE) return -5.0;
            if (Plateau.contientUnJoueur(cell)) return -10.0;
            return (100 - energy) * 0.5 - moulins * 6.0 * W_ENERGIE;
        }

        // Case vide / case de départ
        double valPos = valPos(dest, p, toursRest, ft);
        double pen    = p.donneTourCourant() > SEUIL_ENDGAME
                ? -W_ISOLATION / (distMinAdv(dest.x, dest.y, p) + 1)
                : 0.0;
        return moulins - W_ENERGIE + valPos + pen;
    }

    private double uRien(int energy, int moulins) {
        if (energy == 0)  return -500.0;
        if (moulins == 0) return -50.0;
        return moulins * 1.0;
    }

    private double uManille(int moulins, double ft) {
        return 20 * W_ENERGIE - moulins * 10 * ft;
    }

    // =========================================================================
    // Valeur positionnelle
    // =========================================================================

    private double valPos(Point dest, Plateau p, int toursRest, double ft) {
        double v = 0.0;
        for (Point m : getMoulins(p)) {
            int cell = p.donneContenuCellule(m.x, m.y);
            if (Plateau.donneUtilisateurDeLUniteDeProduction(cell) == donneRang()) continue;
            int dist = distM(dest, m);
            if (dist > DIST_INFLUENCE_MAX) continue;
            double attirance = 2.0 / (dist + 1);
            double gainLong  = Math.max(0, (toursRest - dist) * ft * 0.05 / (dist + 1));
            double risque    = risque(m, p);
            v += (attirance + gainLong) * (1.0 - risque);
        }
        return v;
    }

    // =========================================================================
    // Risque de vol
    // =========================================================================

    private double risque(Point m, Plateau p) {
        double max = AGRESSIVITE_DEFAUT;
        for (int r = 0; r < 4; r++) {
            if (r == donneRang()) continue;
            int    dist = distM(p.donneJoueur(r).donnePosition(), m);
            double agr  = agressivite != null ? agressivite[r] : AGRESSIVITE_DEFAUT;
            max = Math.max(max, agr / (dist + 1.0));
        }
        return Math.min(max, 1.0);
    }

    // =========================================================================
    // BFS
    // =========================================================================

    private Action bfs(Point src, Point dst, Plateau p) {
        if (src.equals(dst)) return Action.RIEN;

        int       taille = p.donneTaille();
        boolean[] vis    = new boolean[taille * taille];
        int[]     parent = new int[taille * taille];
        Arrays.fill(parent, -1);

        int iSrc = idx(src, taille);
        vis[iSrc] = true;
        Queue<Integer> q = new LinkedList<>();
        q.add(iSrc);

        int[][] dirs = {{0,-1},{0,1},{-1,0},{1,0}};

        while (!q.isEmpty()) {
            int cur = q.poll();
            int cc  = cur / taille, cl = cur % taille;

            for (int[] d : dirs) {
                int nc = cc + d[0], nl = cl + d[1];
                if (!p.coordonneeValide(nc, nl)) continue;
                int iN = idx(nc, nl, taille);
                if (vis[iN]) continue;

                boolean estDst = nc == dst.x && nl == dst.y;
                if (!estDst) {
                    int cell = p.donneContenuCellule(nc, nl);
                    if (Plateau.contientUneZoneInfranchissable(cell)) continue;
                    boolean adv = false;
                    for (int r = 0; r < 4 && !adv; r++)
                        adv = r != donneRang() && Plateau.contientLeJoueur(cell, r);
                    if (adv) continue;
                }

                vis[iN]    = true;
                parent[iN] = cur;

                if (estDst) {
                    int c2 = iN;
                    while (parent[c2] != iSrc) c2 = parent[c2];
                    return dirVers(src, new Point(c2 / taille, c2 % taille));
                }
                q.add(iN);
            }
        }
        return Action.RIEN;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Action manilleAdj(Point pos, Plateau p, int[] echanges) {
        for (int r = 0; r < 4; r++) {
            if (r == donneRang()) continue;
            if (!libre(echanges, r)) continue;
            if (enOliveraie(r, p)) continue;
            if (distM(pos, p.donneJoueur(r).donnePosition()) == 1) return Action.RIEN;
        }
        return null;
    }

    private Point caseManillable(Point pos, Plateau p, int[] echanges) {
        int   bestDist = Integer.MAX_VALUE;
        Point best     = null;
        int[][] dirs   = {{0,-1},{0,1},{-1,0},{1,0}};

        for (int r = 0; r < 4; r++) {
            if (r == donneRang() || !libre(echanges, r) || enOliveraie(r, p)) continue;
            Point pa = p.donneJoueur(r).donnePosition();
            for (int[] d : dirs) {
                Point v = new Point(pa.x + d[0], pa.y + d[1]);
                if (!accessibleTransit(v, p)) continue;
                int dist = distM(pos, v);
                if (dist < bestDist) { bestDist = dist; best = v; }
            }
        }
        return best;
    }

    private Point oliveraieProche(Point pos, Plateau p) {
        int   bestDist = Integer.MAX_VALUE;
        Point best     = null;
        int   taille   = p.donneTaille();

        for (int c = 0; c < taille; c++) {
            for (int l = 0; l < taille; l++) {
                int cell = p.donneContenuCellule(c, l);
                if (!Plateau.contientUneUniteDeRessourcage(cell)) continue;
                if (Plateau.contientUnJoueur(cell)) continue;
                int dist = Math.abs(c - pos.x) + Math.abs(l - pos.y);
                if (dist < bestDist) { bestDist = dist; best = new Point(c, l); }
            }
        }
        return best;
    }

    private double distMinAdv(int col, int lig, Plateau p) {
        double min = Double.MAX_VALUE;
        for (int r = 0; r < 4; r++) {
            if (r == donneRang()) continue;
            Point pa = p.donneJoueur(r).donnePosition();
            min = Math.min(min, Math.abs(pa.x - col) + Math.abs(pa.y - lig));
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    // =========================================================================
    // Profils adversariaux
    // =========================================================================

    private void majAgressivite(Plateau p) {
        try {
            if (agressivite == null) return;
            Point[] moulins = getMoulins(p);

            for (int r = 0; r < 4; r++) {
                if (r == donneRang()) continue;
                Point cur  = p.donneJoueur(r).donnePosition();
                Point prev = positionsPrecedentes[r];

                if (prev != null) {
                    double dAvant = distMinMoulinsLibres(prev, r, moulins, p);
                    double dApres = distMinMoulinsLibres(cur,  r, moulins, p);
                    agressivite[r] = dApres < dAvant
                            ? Math.min(1.0, agressivite[r] + 0.05)
                            : Math.max(0.1, agressivite[r] - 0.01);

                    int cell = p.donneContenuCellule(cur.x, cur.y);
                    if (Plateau.contientUneUniteDeProduction(cell)
                            && Plateau.donneUtilisateurDeLUniteDeProduction(cell) == r) {
                        int prevCell = p.donneContenuCellule(prev.x, prev.y);
                        boolean etaitDejaSurSonMoulin =
                                Plateau.contientUneUniteDeProduction(prevCell)
                                        && Plateau.donneUtilisateurDeLUniteDeProduction(prevCell) == r;
                        if (!etaitDejaSurSonMoulin)
                            agressivite[r] = Math.min(1.0, agressivite[r] + 0.2);
                    }
                }
                positionsPrecedentes[r] = new Point(cur);
            }
        } catch (Exception ex) {
            log("majAgressivite : " + ex.getMessage());
        }
    }

    private double distMinMoulinsLibres(Point pos, int rangAdv,
                                        Point[] moulins, Plateau p) {
        double min = Double.MAX_VALUE;
        for (Point m : moulins) {
            int cell = p.donneContenuCellule(m.x, m.y);
            if (Plateau.donneUtilisateurDeLUniteDeProduction(cell) == rangAdv) continue;
            min = Math.min(min, distM(pos, m));
        }
        return min == Double.MAX_VALUE ? 999.0 : min;
    }

    // =========================================================================
    // Initialisation
    // =========================================================================

    private void init(Plateau p) {
        if (agressivite != null) return;

        agressivite          = new double[4];
        Arrays.fill(agressivite, AGRESSIVITE_DEFAUT);
        positionsPrecedentes = new Point[4];

        int    taille  = p.donneTaille();
        int    nbMoul  = getMoulins(p).length;
        double densite = (double) nbMoul / (taille * taille);
        nbMoulinsCible = densite > 0.05 ? 3 : NB_MOULINS_BASE;
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    private Joueur moi(Plateau p) {
        return p.donneJoueur(donneRang());
    }

    private Point[] getMoulins(Plateau p) {
        if (cacheMoulins != null) return cacheMoulins;
        ArrayList<Point> liste = new ArrayList<>();
        int t = p.donneTaille();
        for (int c = 0; c < t; c++)
            for (int l = 0; l < t; l++) {
                int cell = p.donneContenuCellule(c, l);
                if (Plateau.contientUneUniteDeProduction(cell))
                    liste.add(new Point(c, l));
            }
        cacheMoulins = liste.toArray(new Point[0]);
        return cacheMoulins;
    }

    private boolean enOliveraie(int r, Plateau p) {
        Point pos  = p.donneJoueur(r).donnePosition();
        int   cell = p.donneContenuCellule(pos.x, pos.y);
        return Plateau.contientUneUniteDeRessourcage(cell);
    }

    private boolean libre(int[] echanges, int r) {
        return echanges[r] == 0;
    }

    /**
     * Accessibilité pour le transit BFS et l'esquive.
     * Bloque rochers ET moulins (on ne traverse pas un moulin, on s'y arrête).
     */
    private boolean accessibleTransit(Point pt, Plateau p) {
        if (!p.coordonneeValide(pt.x, pt.y)) return false;
        int cell = p.donneContenuCellule(pt.x, pt.y);
        if (Plateau.contientUneZoneInfranchissable(cell)) return false;
        for (int r = 0; r < 4; r++)
            if (r != donneRang() && Plateau.contientLeJoueur(cell, r)) return false;
        return true;
    }

    private Point dest(Point pos, Action a) {
        return switch (a) {
            case HAUT   -> new Point(pos.x,     pos.y - 1);
            case BAS    -> new Point(pos.x,     pos.y + 1);
            case GAUCHE -> new Point(pos.x - 1, pos.y);
            case DROITE -> new Point(pos.x + 1, pos.y);
            default     -> new Point(pos.x,     pos.y);
        };
    }

    private static Action[] deplacements() {
        return new Action[]{Action.HAUT, Action.BAS, Action.GAUCHE, Action.DROITE};
    }

    private Action dirVers(Point src, Point voisin) {
        int dc = voisin.x - src.x, dl = voisin.y - src.y;
        if (dc ==  1) return Action.DROITE;
        if (dc == -1) return Action.GAUCHE;
        if (dl ==  1) return Action.BAS;
        if (dl == -1) return Action.HAUT;
        return Action.RIEN;
    }

    private int distM(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    private int idx(Point p, int t)      { return p.x * t + p.y; }
    private int idx(int c, int l, int t) { return c * t + l; }

    private void log(String msg) {
        // System.err.println("[MonJoueur] " + msg);
    }
}