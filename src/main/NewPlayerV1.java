import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewPlayerV1 extends Joueur {

    // ── Coûts du jeu ──────────────────────────────────────────────────────────
    private static final int    COUT_CAPTURE        = 20;
    /** energie - 20 >= 1 obligatoire après capture → plancher absolu = 21. */
    private static final int    ENERGIE_MIN_CAPTURE = COUT_CAPTURE + 1;

    // ── Seuils d'énergie ──────────────────────────────────────────────────────
    private static final int    SEUIL_CRITIQUE_BASE = 40;
    private static final int    SEUIL_PREVENTIF     = 70;
    private static final int    MARGE_SECURITE      = 10;
    /**
     * Buffer au-dessus de la distance min oliveraie pour le seuil critique dynamique.
     * Garantit qu'on peut TOUJOURS atteindre une oliveraie avant d'être à 0.
     */
    private static final int    BUFFER_OLIVERAIE    = 8;
    /**
     * Buffer de sécurité ajouté à la distance Manhattan moulin→oliveraie
     * dans peutCapturerEtRentrer() pour compenser les détours d'obstacles.
     */
    private static final int    BUFFER_RETOUR       = 6;

    // ── Phases de jeu ─────────────────────────────────────────────────────────
    /** Tours 0 à FIN_RUSH : phase de rush, scoring simplifié, captures rapides. */
    private static final int    FIN_RUSH            = 80;
    /** Sous ce nombre de tours restants : mode agressif (marge supprimée). */
    private static final int    SEUIL_FIN_PARTIE    = 50;

    // ── Scoring moulins ───────────────────────────────────────────────────────
    private static final int    RAYON_CLUSTER           = 5;
    private static final double POIDS_CLUSTER           = 2.5;
    private static final double POIDS_CLUSTER_RUSH      = 1.0; // réduit en phase rush
    private static final double BONUS_ENNEMI            = 1.4;
    private static final double MALUS_CONTESTE          = 0.6;
    private static final int    RAYON_DANGER            = 3;

    // ── Lookahead ─────────────────────────────────────────────────────────────
    private static final int    PROFONDEUR_LOOKAHEAD    = 3;
    private static final double DEPRECIATION_LOOKAHEAD  = 0.5;

    // ── Oliveraie ─────────────────────────────────────────────────────────────
    /** Séquence de gains : tour 1→+10, 2→+20, 3→+60, 4→+20, 5→+10. */
    private static final int[]  GAIN_OLIVERAIE          = {10, 20, 60, 20, 10};
    /** Cases libres adjacentes ≤ ce seuil → on est dans un couloir. */
    private static final int    SEUIL_COULOIR           = 2;

    // ── État interne (réinitialisé par debutDePartie) ─────────────────────────
    private boolean enTrainDeRecolter  = false;
    private int     toursEnOliveraie   = 0;
    private int     toursOptimaux      = 0;
    /**
     * Oliveraie cible mémorisée : évite de changer de cap à chaque tour
     * et de naviguer en zigzag entre plusieurs oliveraies.
     * Remise à null quand on arrive dessus ou si elle est occupée.
     */
    private Point   cibleOliveraie     = null;

    /**
     * Cache des distances A* pour le tour courant.
     * Clé : 4 coordonnées encodées en Long (5 bits chacune, valide ≤ 32×32).
     * Vidé à chaque faitUneAction() car les positions adversaires changent.
     *
     * Impact : le lookahead 3 niveaux peut appeler distance() des dizaines de fois
     * par tour. Sans cache, ce seraient autant d'appels A* redondants.
     */
    private final Map<Long, Integer> cacheDistances = new HashMap<>(256);

    private final PlateauAnalyser analyser;

    // ─────────────────────────────────────────────────────────────────────────

    public NewPlayerV1(String sonNom) {
        super(sonNom);
        this.analyser = new PlateauAnalyser();
    }

    /**
     * Réinitialise tout l'état interne entre les parties du tournoi.
     * Le MaitreDuJeu réutilise la même instance sans recréer le joueur.
     */
    @Override
    public void debutDePartie(int rang) {
        super.debutDePartie(rang);
        enTrainDeRecolter = false;
        toursEnOliveraie  = 0;
        toursOptimaux     = 0;
        cibleOliveraie    = null;
        cacheDistances.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Action faitUneAction(Plateau plateau) {
        // Cache vidé à chaque tour (positions adversaires ont changé)
        cacheDistances.clear();
        analyser.analysePlateau(plateau, this);

        final Point   pos           = donnePosition();
        final int     energie       = donneRessources();
        final int     tourCourant   = plateau.donneTourCourant();
        final int     toursRestants = plateau.donneNombreDeTours() - tourCourant;
        final int     nbMoulins     = analyser.nombreMoulinsNous;
        final boolean finPartie     = toursRestants <= SEUIL_FIN_PARTIE;
        final boolean phaseRush     = tourCourant <= FIN_RUSH;

        // Seuil critique dynamique : garder toujours assez pour rejoindre une oliveraie.
        // distanceMinOliveraie utilise Manhattan (rapide) comme approximation.
        final int distMinOliveraie = distanceMinOliveraie(pos);
        final int seuilCritique    = Math.max(SEUIL_CRITIQUE_BASE,
                distMinOliveraie + BUFFER_OLIVERAIE);

        // ── Machine à états : récolte en oliveraie ────────────────────────────
        // IMPORTANT : une fois enTrainDeRecolter=true, on ne sort qu'une fois
        // toursOptimaux atteint ou énergie pleine. On ne sort jamais prématurément.
        if (enTrainDeRecolter) {
            if (estSurOliveraie(plateau, pos)) {
                toursEnOliveraie++;
                if (energie >= 100 || toursEnOliveraie >= toursOptimaux) {
                    terminerRecolte(); // Ce tour on peut chercher un moulin
                } else {
                    return Action.RIEN; // Rester immobile dans l'oliveraie
                }
            } else {
                terminerRecolte(); // Sécurité : déplacé hors oliveraie (ne doit pas arriver)
            }
        }

        // ── Priorité 0 : énergie nulle → survie absolue ───────────────────────
        // À 0 : on ne peut pas capturer (besoin de 20 min) mais on peut se déplacer.
        if (energie == 0) {
            cibleOliveraie = null;
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        // ── Mode fin de partie ────────────────────────────────────────────────
        if (finPartie) {
            cibleOliveraie = null;
            return faitUneActionFinDePartie(plateau, pos, energie, toursRestants,
                    nbMoulins, distMinOliveraie);
        }

        // ─── MODE NORMAL / RUSH ───────────────────────────────────────────────

        // Priorité 1 : déjà sur oliveraie et énergie incomplète → commencer récolte
        if (estSurOliveraie(plateau, pos) && energie < SEUIL_PREVENTIF) {
            cibleOliveraie = null;
            demarrerRecolte(energie, nbMoulins, toursRestants);
            return Action.RIEN;
        }

        // Priorité 2 : énergie trop basse → recharger d'urgence
        if (energie <= seuilCritique) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        // Priorité 3 : capturer le meilleur moulin
        Point cible = phaseRush
                ? trouveMeilleurMoulinRush(plateau, pos, energie, toursRestants, distMinOliveraie)
                : trouveMeilleurMoulin(plateau, pos, energie, toursRestants, false, distMinOliveraie);

        if (cible != null) {
            cibleOliveraie = null;
            Action a = allerVers(plateau, pos, cible);
            if (a != null) return a;
        }

        // Priorité 4 : recharge préventive si énergie insuffisante
        if (energie < SEUIL_PREVENTIF) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        return Action.RIEN;
    }

    // =========================================================================
    //  PHASE RUSH (tours 0 – FIN_RUSH)
    // =========================================================================

    /**
     * En phase de rush, tous les moulins sont libres et les adversaires sont loin.
     * Objectif : maximiser les captures par énergie dépensée.
     *
     * Scoring simplifié :
     *   score = toursGain / (dist + 1) + clusterProches × POIDS_CLUSTER_RUSH
     *
     * Pas de lookahead (inutile quand tous les moulins sont libres et proches).
     * Vérification énergie retour : peutCapturerEtRentrer() obligatoire.
     */
    private Point trouveMeilleurMoulinRush(Plateau plateau, Point pos, int energie,
                                           int toursRestants, int distMinOliveraie) {
        Point  meilleur = null;
        double mScore   = Double.NEGATIVE_INFINITY;

        for (Point cible : analyser.moulinsLibres) {
            int dist = distanceCachee(plateau, pos, cible);
            if (dist <= 0) continue;
            if (energie < dist + ENERGIE_MIN_CAPTURE) continue;
            if (!peutCapturerEtRentrer(energie, dist, cible)) continue;

            int toursGain = toursRestants - dist;
            if (toursGain <= 0) continue;

            double score = (double) toursGain / (dist + 1.0);
            score += compteMoulinsProches(cible) * POIDS_CLUSTER_RUSH;

            if (score > mScore) {
                mScore   = score;
                meilleur = cible;
            }
        }
        return meilleur;
    }

    // =========================================================================
    //  MODE FIN DE PARTIE AGRESSIF
    // =========================================================================

    /**
     * Sous SEUIL_FIN_PARTIE tours : marge de sécurité supprimée.
     * Invariant maintenu : energie - 20 >= 1 après capture (jamais tomber à 0).
     * Vérification retour oliveraie toujours active.
     */
    private Action faitUneActionFinDePartie(Plateau plateau, Point pos, int energie,
                                            int toursRestants, int nbMoulins,
                                            int distMinOliveraie) {
        if (energie < ENERGIE_MIN_CAPTURE) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        Point cible = trouveMeilleurMoulin(plateau, pos, energie, toursRestants,
                true, distMinOliveraie);
        if (cible != null) {
            Action a = allerVers(plateau, pos, cible);
            if (a != null) return a;
        }
        return Action.RIEN;
    }

    // =========================================================================
    //  SCORING PRINCIPAL : MEILLEUR MOULIN (lookahead 3 niveaux)
    // =========================================================================

    /**
     * Sélectionne le moulin optimal selon :
     *
     *   score = [ (toursRestants - dist) + cluster×2.5 + lookahead3 ]
     *           × bonusEnnemi × malusContestation / (dist + 1)
     *
     * Deux contraintes énergétiques cumulatives :
     *   1. energie >= dist + ENERGIE_MIN_CAPTURE [+ MARGE_SECURITE en mode normal]
     *      → ne jamais tomber à 0 après capture
     *   2. peutCapturerEtRentrer() → après capture, pouvoir atteindre une oliveraie
     *      → éviter le blocage en zone éloignée sans énergie
     */
    private Point trouveMeilleurMoulin(Plateau plateau, Point pos, int energie,
                                       int toursRestants, boolean modeAgressif,
                                       int distMinOliveraie) {
        final int margeMin = modeAgressif
                ? ENERGIE_MIN_CAPTURE
                : ENERGIE_MIN_CAPTURE + MARGE_SECURITE;

        Point  meilleur = null;
        double mScore   = Double.NEGATIVE_INFINITY;

        List<Point> candidats = new ArrayList<>(analyser.moulinsLibres);
        candidats.addAll(analyser.moulinsAdverses);

        for (Point cible : candidats) {
            int dist = distanceCachee(plateau, pos, cible);
            if (dist <= 0) continue;
            if (energie < dist + margeMin) continue;
            if (!peutCapturerEtRentrer(energie, dist, cible)) continue;

            int toursGain = toursRestants - dist;
            if (toursGain <= 0) continue;

            boolean ennemi = analyser.moulinsAdverses.contains(cible);

            double score = toursGain;
            score += compteMoulinsProches(cible) * POIDS_CLUSTER;

            int energieApres = energie - dist - COUT_CAPTURE;
            score += scoreLookahead(plateau, cible, energieApres,
                    toursRestants - dist, PROFONDEUR_LOOKAHEAD,
                    DEPRECIATION_LOOKAHEAD, modeAgressif);

            if (ennemi)                       score *= BONUS_ENNEMI;
            if (adversaireProcheDeCase(cible)) score *= MALUS_CONTESTE;
            score /= (dist + 1.0);

            if (score > mScore) {
                mScore   = score;
                meilleur = cible;
            }
        }
        return meilleur;
    }

    /**
     * Vérifie qu'après avoir marché dist pas + capturé (coût 20),
     * on dispose encore d'assez d'énergie pour rejoindre une oliveraie.
     *
     * Utilise Manhattan × facteur comme estimation conservative :
     * Manhattan sous-estime toujours A* (pas d'obstacles), donc on ajoute
     * BUFFER_RETOUR pour absorber les détours réels.
     *
     * Condition : energieApresCapture > distManhattanMinOliveraie + BUFFER_RETOUR
     */
    private boolean peutCapturerEtRentrer(int energie, int distMoulin, Point moulin) {
        int energieApresCapture = energie - distMoulin - COUT_CAPTURE;
        if (energieApresCapture < 1) return false;

        int distOlivMoulin = Integer.MAX_VALUE;
        for (Point o : analyser.oliveraies) {
            if (analyser.positionsAdversaires.contains(o)) continue;
            int d = distanceManhattan(moulin, o);
            if (d < distOlivMoulin) distOlivMoulin = d;
        }

        if (distOlivMoulin == Integer.MAX_VALUE) return energieApresCapture >= 1;
        return energieApresCapture > distOlivMoulin + BUFFER_RETOUR;
    }

    /**
     * Lookahead récursif 3 niveaux avec cache de distances.
     * Déprécie chaque niveau par DEPRECIATION_LOOKAHEAD (incertitude croissante).
     */
    private double scoreLookahead(Plateau plateau, Point depart, int energieApres,
                                  int toursApres, int profondeur, double poids,
                                  boolean modeAgressif) {
        if (profondeur == 0 || energieApres < ENERGIE_MIN_CAPTURE || toursApres <= 0) return 0;

        final int margeMin = modeAgressif
                ? ENERGIE_MIN_CAPTURE
                : ENERGIE_MIN_CAPTURE + MARGE_SECURITE;

        Point  bestCible = null;
        int    bestDist  = 0;
        double best      = 0;

        List<Point> candidats = new ArrayList<>(analyser.moulinsLibres);
        candidats.addAll(analyser.moulinsAdverses);

        for (Point cible : candidats) {
            if (cible.equals(depart)) continue;
            int dist = distanceCachee(plateau, depart, cible);
            if (dist <= 0) continue;
            if (energieApres < dist + margeMin) continue;
            int tg = toursApres - dist;
            if (tg <= 0) continue;

            boolean ennemi = analyser.moulinsAdverses.contains(cible);
            double s = (double) tg / (dist + 1.0);
            if (ennemi) s *= BONUS_ENNEMI;

            if (s > best) {
                best      = s;
                bestCible = cible;
                bestDist  = dist;
            }
        }

        if (bestCible == null) return 0;

        double scoreNiveau     = best * poids;
        int    energieSuivante = energieApres - bestDist - COUT_CAPTURE;
        scoreNiveau += scoreLookahead(plateau, bestCible, energieSuivante,
                toursApres - bestDist, profondeur - 1,
                poids * DEPRECIATION_LOOKAHEAD, modeAgressif);
        return scoreNiveau;
    }

    // =========================================================================
    //  OLIVERAIE
    // =========================================================================

    /**
     * Navigue vers la meilleure oliveraie disponible ou reste dessus pour recharger.
     *
     * Persistance de cibleOliveraie : une fois une oliveraie choisie, on ne
     * change de cap que si elle devient occupée par un adversaire.
     * Évite les oscillations entre deux oliveraies équidistantes.
     */
    private Action allerVersOliveraieOuRester(Plateau plateau, Point pos, int energie,
                                              int nbMoulins, int toursRestants) {
        // Invalider la cible si occupée
        if (cibleOliveraie != null && analyser.positionsAdversaires.contains(cibleOliveraie)) {
            cibleOliveraie = null;
        }

        // Calculer une nouvelle cible si nécessaire
        if (cibleOliveraie == null) {
            cibleOliveraie = trouveOliveraieOptimale(plateau, pos, energie);
        }

        if (cibleOliveraie == null) return Action.RIEN;

        // Arrivée sur l'oliveraie : démarrer la récolte
        if (pos.equals(cibleOliveraie)) {
            cibleOliveraie = null;
            demarrerRecolte(energie, nbMoulins, toursRestants);
            return Action.RIEN;
        }

        Action a = allerVers(plateau, pos, cibleOliveraie);
        return (a != null) ? a : Action.RIEN;
    }

    /**
     * Meilleure oliveraie = max(gainSimulé / distanceA*).
     *
     * gainSimulé est identique pour toutes les oliveraies (dépend uniquement
     * de l'énergie actuelle), donc le ratio revient à minimiser la distance A*.
     * Mais contrairement à Manhattan, la distance A* tient compte des obstacles.
     *
     * Exclut les oliveraies occupées par un adversaire.
     */
    private Point trouveOliveraieOptimale(Plateau plateau, Point pos, int energieActuelle) {
        if (analyser.oliveraies.isEmpty()) return null;

        for (Point o : analyser.oliveraies) {
            if (pos.equals(o)) return o;
        }

        int    gain          = simulerGainOliveraie(energieActuelle);
        Point  meilleure     = null;
        double meilleurRatio = -1.0;

        for (Point o : analyser.oliveraies) {
            if (analyser.positionsAdversaires.contains(o)) continue;

            int dist = distanceCachee(plateau, pos, o);
            if (dist <= 0 || gain <= 0) continue;

            double ratio = (double) gain / dist;
            if (ratio > meilleurRatio) {
                meilleurRatio = ratio;
                meilleure     = o;
            }
        }
        return meilleure;
    }

    /**
     * Simule le gain total d'énergie de la séquence 10/20/60/20/10
     * depuis l'énergie actuelle, cappé à 100.
     */
    private int simulerGainOliveraie(int energieActuelle) {
        int sim   = energieActuelle;
        int total = 0;
        for (int g : GAIN_OLIVERAIE) {
            if (sim >= 100) break;
            int r = Math.min(g, 100 - sim);
            total += r;
            sim   += r;
        }
        return total;
    }

    private void demarrerRecolte(int energie, int nbMoulins, int toursRestants) {
        toursOptimaux     = calculerToursOptimaux(energie, nbMoulins, toursRestants);
        enTrainDeRecolter = true;
        toursEnOliveraie  = 1;
    }

    private void terminerRecolte() {
        enTrainDeRecolter = false;
        toursEnOliveraie  = 0;
        toursOptimaux     = 0;
    }

    /**
     * Durée optimale en oliveraie.
     * On reste un tour supplémentaire si gainReel × valeurEnergie >= coût.
     *
     * valeurEnergie = toursRestants × max(1, nbMoulins) / 100
     * Avec N moulins, chaque point d'énergie permet N bouteilles de plus
     * → les séjours raccourcissent naturellement quand le stock de moulins augmente.
     */
    private int calculerToursOptimaux(int energieActuelle, int nbMoulins, int toursRestants) {
        double valeurEnergie = (toursRestants * (double) Math.max(1, nbMoulins)) / 100.0;
        int    energieSim    = energieActuelle;
        int    toursRester   = 0;

        for (int gain : GAIN_OLIVERAIE) {
            if (energieSim >= 100) break;
            int    gainReel = Math.min(gain, 100 - energieSim);
            double benefice = gainReel * valeurEnergie;
            double cout     = Math.max(1, nbMoulins);

            if (benefice >= cout || energieSim < 30) {
                toursRester++;
                energieSim += gainReel;
            } else {
                break;
            }
        }
        return Math.max(1, toursRester);
    }

    private boolean estSurOliveraie(Plateau plateau, Point pos) {
        if (pos == null) return false;
        return Plateau.contientUneUniteDeRessourcage(
                plateau.donneContenuCelluleSansJoueur(pos.x, pos.y));
    }

    /**
     * Distance Manhattan minimum jusqu'à une oliveraie libre (approximation rapide).
     * Utilisée pour le seuil critique dynamique : évite un A* coûteux à chaque tour.
     */
    private int distanceMinOliveraie(Point pos) {
        int dMin = Integer.MAX_VALUE;
        for (Point o : analyser.oliveraies) {
            if (analyser.positionsAdversaires.contains(o)) continue;
            if (pos.equals(o)) return 0;
            int d = distanceManhattan(pos, o);
            if (d < dMin) dMin = d;
        }
        return dMin == Integer.MAX_VALUE ? 0 : dMin;
    }

    // =========================================================================
    //  NAVIGATION : évitement total de la manille
    // =========================================================================

    /**
     * Premier pas A* vers la cible avec 3 niveaux d'évitement :
     *   Niveau 1 : positions adversaires + 4 cases adjacentes (évitement manille complet)
     *   Niveau 2 : positions adversaires seulement
     *   Niveau 3 : chemin brut (dernier recours)
     *
     * Détection couloir (#9) : si ≤ SEUIL_COULOIR cases libres autour de nous,
     * on court-circuite l'évitement pour ne pas osciller dans un passage étroit.
     */
    private Action allerVers(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null) return null;
        if (depart.equals(cible))            return Action.RIEN;

        ArrayList<Noeud> chemin;

        if (estDansCouloir(plateau, depart)) {
            chemin = plateau.donneCheminEntre(depart, cible);
        } else {
            chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                    depart, cible, construireObstaclesComplets(plateau));

            if (chemin == null || chemin.isEmpty()) {
                chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                        depart, cible, construireObstaclesPositions());
            }
            if (chemin == null || chemin.isEmpty()) {
                chemin = plateau.donneCheminEntre(depart, cible);
            }
        }

        if (chemin == null || chemin.isEmpty()) return null;
        return directionVers(depart, chemin.get(0).getX(), chemin.get(0).getY());
    }

    private boolean estDansCouloir(Plateau plateau, Point pos) {
        int[][] dirs  = {{0,1},{0,-1},{1,0},{-1,0}};
        int     libres = 0;
        for (int[] d : dirs) {
            int nx = pos.x + d[0], ny = pos.y + d[1];
            if (!plateau.coordonneeValide(nx, ny)) continue;
            if (!Plateau.contientUneZoneInfranchissable(
                    plateau.donneContenuCelluleSansJoueur(nx, ny))) libres++;
        }
        return libres <= SEUIL_COULOIR;
    }

    private List<Noeud> construireObstaclesComplets(Plateau plateau) {
        List<Noeud> obstacles = new ArrayList<>();
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
        for (Point p : analyser.positionsAdversaires) {
            obstacles.add(new Noeud(p.x, p.y));
            for (int[] d : dirs) {
                int nx = p.x + d[0], ny = p.y + d[1];
                if (plateau.coordonneeValide(nx, ny))
                    obstacles.add(new Noeud(nx, ny));
            }
        }
        return obstacles;
    }

    private List<Noeud> construireObstaclesPositions() {
        List<Noeud> obs = new ArrayList<>();
        for (Point p : analyser.positionsAdversaires)
            obs.add(new Noeud(p.x, p.y));
        return obs;
    }

    // =========================================================================
    //  UTILITAIRES
    // =========================================================================

    /**
     * Distance A* avec cache par tour.
     * Clé : 4 × 5 bits = 20 bits (valide pour cartes ≤ 32×32).
     * Encode : (x1 << 15) | (y1 << 10) | (x2 << 5) | y2
     */
    private int distanceCachee(Plateau plateau, Point depart, Point arrivee) {
        if (depart == null || arrivee == null) return -1;
        if (depart.equals(arrivee)) return 0;

        long cle = ((long) depart.x  << 15)
                | ((long) depart.y  << 10)
                | ((long) arrivee.x <<  5)
                |  (long) arrivee.y;

        Integer cached = cacheDistances.get(cle);
        if (cached != null) return cached;

        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, arrivee);
        int dist = (chemin == null) ? -1 : chemin.size();
        cacheDistances.put(cle, dist);
        return dist;
    }

    private int distanceManhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    private int compteMoulinsProches(Point centre) {
        int count = 0;
        for (Point m : analyser.moulinsLibres)
            if (!m.equals(centre) && distanceManhattan(centre, m) <= RAYON_CLUSTER) count++;
        for (Point m : analyser.moulinsAdverses)
            if (!m.equals(centre) && distanceManhattan(centre, m) <= RAYON_CLUSTER) count++;
        return count;
    }

    private boolean adversaireProcheDeCase(Point cible) {
        for (Point p : analyser.positionsAdversaires)
            if (distanceManhattan(p, cible) <= RAYON_DANGER) return true;
        return false;
    }

    private Action directionVers(Point depart, int nx, int ny) {
        int dx = nx - depart.x, dy = ny - depart.y;
        if (dx > 0) return Action.DROITE;
        if (dx < 0) return Action.GAUCHE;
        if (dy > 0) return Action.BAS;
        if (dy < 0) return Action.HAUT;
        return Action.RIEN;
    }
}