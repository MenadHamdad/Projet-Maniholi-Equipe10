import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PlayerV1 extends Joueur {

    // ── Coûts du jeu (règles exactes) ─────────────────────────────────────────
    private static final int    COUT_CAPTURE        = 20;
    /**
     * Énergie minimale avant capture : energie - 20 >= 1 obligatoire
     * pour continuer à produire des bouteilles après la capture.
     */
    private static final int    ENERGIE_MIN_CAPTURE = COUT_CAPTURE + 1; // 21

    // ── Seuils d'énergie (mode normal) ────────────────────────────────────────
    private static final int    SEUIL_CRITIQUE      = 40;
    private static final int    SEUIL_PREVENTIF     = 70;
    private static final int    MARGE_SECURITE      = 10;

    // ── Fin de partie ─────────────────────────────────────────────────────────
    /** Sous ce seuil, MARGE_SECURITE est supprimée (mode agressif). */
    private static final int    SEUIL_FIN_PARTIE    = 50;

    // ── Scoring moulins ───────────────────────────────────────────────────────
    private static final int    RAYON_CLUSTER       = 5;
    private static final double POIDS_CLUSTER       = 2.5;
    private static final double BONUS_ENNEMI        = 1.4;
    private static final double MALUS_CONTESTE      = 0.6;
    private static final int    RAYON_DANGER        = 3;

    // ── Lookahead 3 niveaux ───────────────────────────────────────────────────
    private static final int    PROFONDEUR_LOOKAHEAD   = 3;
    private static final double DEPRECIATION_LOOKAHEAD = 0.5;

    // ── Oliveraie ─────────────────────────────────────────────────────────────
    /**
     * Séquence de gain d'énergie par tour en oliveraie.
     * Tour 1→+10, tour 2→+20, tour 3→+60, tour 4→+20, tour 5→+10.
     */
    private static final int[]  GAIN_OLIVERAIE      = {10, 20, 60, 20, 10};

    /**
     * Seuil de cases libres adjacentes en dessous duquel on considère
     * être dans un couloir (#9). Dans un couloir, le sur-évitement des
     * adjacences adverses provoquerait une oscillation → on saute au chemin brut.
     */
    private static final int    SEUIL_COULOIR       = 2;

    // ── État interne (réinitialisé via debutDePartie) ─────────────────────────
    private boolean enTrainDeRecolter = false;
    private int     toursEnOliveraie  = 0;
    private int     toursOptimaux     = 0;

    private final PlateauAnalyser analyser;

    // ─────────────────────────────────────────────────────────────────────────

    public PlayerV1(String sonNom) {
        super(sonNom);
        this.analyser = new PlateauAnalyser();
    }

    /**
     * Réinitialise l'état interne entre les parties du tournoi.
     * Le MaitreDuJeu réutilise la même instance sans recréer le joueur.
     */
    @Override
    public void debutDePartie(int rang) {
        super.debutDePartie(rang);
        enTrainDeRecolter = false;
        toursEnOliveraie  = 0;
        toursOptimaux     = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Action faitUneAction(Plateau plateau) {
        analyser.analysePlateau(plateau, this);

        final Point   pos          = donnePosition();
        final int     energie      = donneRessources();
        final int     tourCourant  = plateau.donneTourCourant();
        final int     toursRestants = plateau.donneNombreDeTours() - tourCourant;
        final int     nbMoulins    = analyser.nombreMoulinsNous;
        final boolean finPartie    = toursRestants <= SEUIL_FIN_PARTIE;

        // ── Récolte en oliveraie en cours ─────────────────────────────────────
        if (enTrainDeRecolter) {
            if (estSurOliveraie(plateau, pos)) {
                toursEnOliveraie++;
                if (energie >= 100 || toursEnOliveraie >= toursOptimaux) {
                    terminerRecolte(); // on continue ce tour vers un moulin
                } else {
                    return Action.RIEN;
                }
            } else {
                terminerRecolte(); // sécurité : on n'est plus sur l'oliveraie
            }
        }

        // ── Priorité 0 : énergie nulle → oliveraie (impossible de capturer) ───
        if (energie == 0) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        // ── Mode fin de partie ────────────────────────────────────────────────
        if (finPartie) {
            return faitUneActionFinDePartie(plateau, pos, energie, toursRestants, nbMoulins);
        }

        // ─── MODE NORMAL ──────────────────────────────────────────────────────

        // Priorité 1 : déjà sur oliveraie et énergie basse → rester
        if (estSurOliveraie(plateau, pos) && energie < SEUIL_PREVENTIF) {
            demarrerRecolte(energie, nbMoulins, toursRestants);
            return Action.RIEN;
        }

        // Priorité 2 : énergie critique → recharger
        if (energie <= SEUIL_CRITIQUE) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        // Priorité 3 : capturer le meilleur moulin
        Point cible = trouveMeilleurMoulin(plateau, pos, energie, toursRestants, false);
        if (cible != null) {
            Action a = allerVers(plateau, pos, cible);
            if (a != null) return a;
        }

        // Priorité 4 : recharge préventive
        if (energie < SEUIL_PREVENTIF) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        return Action.RIEN;
    }

    // =========================================================================
    //  MODE FIN DE PARTIE AGRESSIF
    // =========================================================================

    /**
     * Sous SEUIL_FIN_PARTIE tours : marge supprimée, mais ENERGIE_MIN_CAPTURE
     * toujours garantie pour ne jamais tomber à 0 (= 0 bouteille ce tour).
     */
    private Action faitUneActionFinDePartie(Plateau plateau, Point pos, int energie,
                                            int toursRestants, int nbMoulins) {
        if (energie < ENERGIE_MIN_CAPTURE) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        Point cible = trouveMeilleurMoulin(plateau, pos, energie, toursRestants, true);
        if (cible != null) {
            Action a = allerVers(plateau, pos, cible);
            if (a != null) return a;
        }

        return Action.RIEN;
    }

    // =========================================================================
    //  SCORING : MEILLEUR MOULIN (lookahead 3 niveaux)
    // =========================================================================

    /**
     * Sélectionne le moulin (libre ou ennemi) avec le meilleur score :
     *
     *   score = [ (toursRestants - dist) + cluster×2.5 + lookahead3 ]
     *           × bonusEnnemi × malusContestation
     *           / (dist + 1)
     *
     * Contrainte énergie : energie >= dist + ENERGIE_MIN_CAPTURE [+ MARGE_SECURITE]
     * Garantit : energie - dist - 20 >= 1 après capture.
     */
    private Point trouveMeilleurMoulin(Plateau plateau, Point pos, int energie,
                                       int toursRestants, boolean modeAgressif) {
        final int margeMin = modeAgressif
                ? ENERGIE_MIN_CAPTURE
                : ENERGIE_MIN_CAPTURE + MARGE_SECURITE;

        Point  meilleur = null;
        double mScore   = Double.NEGATIVE_INFINITY;

        List<Point> candidats = new ArrayList<>(analyser.moulinsLibres);
        candidats.addAll(analyser.moulinsAdverses);

        for (Point cible : candidats) {
            int dist = distance(plateau, pos, cible);
            if (dist <= 0) continue;
            if (energie < dist + margeMin) continue;

            int toursGain = toursRestants - dist;
            if (toursGain <= 0) continue;

            boolean ennemi = analyser.moulinsAdverses.contains(cible);

            double score = toursGain;
            score += compteMoulinsProches(cible) * POIDS_CLUSTER;

            int energieApres = energie - dist - COUT_CAPTURE;
            score += scoreLookahead(plateau, cible, energieApres,
                    toursRestants - dist, PROFONDEUR_LOOKAHEAD,
                    DEPRECIATION_LOOKAHEAD, modeAgressif);

            if (ennemi)                      score *= BONUS_ENNEMI;
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
     * Lookahead récursif sur PROFONDEUR_LOOKAHEAD niveaux.
     * Chaque niveau est déprécié par DEPRECIATION_LOOKAHEAD (incertitude croissante).
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
            int dist = distance(plateau, depart, cible);
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

    private Action allerVersOliveraieOuRester(Plateau plateau, Point pos, int energie,
                                              int nbMoulins, int toursRestants) {
        Point oliveraie = trouveOliveraieOptimale(plateau, pos, energie);
        if (oliveraie == null) return Action.RIEN;

        if (pos.equals(oliveraie)) {
            demarrerRecolte(energie, nbMoulins, toursRestants);
            return Action.RIEN;
        }
        Action a = allerVers(plateau, pos, oliveraie);
        return (a != null) ? a : Action.RIEN;
    }

    /**
     * Sélectionne la meilleure oliveraie selon le ratio gain_espéré / distance_réelle.
     *
     * Trois améliorations combinées :
     *   #2 : Exclut les oliveraies occupées par un adversaire.
     *   #7 : Score = gainTotal_espéré / distance_A* (et non la plus proche en Manhattan).
     *        Une oliveraie éloignée mais très rechargée peut battre une proche mais peu utile.
     *        Le gain espéré est simulé depuis l'énergie actuelle pour être précis.
     */
    private Point trouveOliveraieOptimale(Plateau plateau, Point pos, int energieActuelle) {
        if (analyser.oliveraies.isEmpty()) return null;

        // Déjà dessus → retour immédiat
        for (Point o : analyser.oliveraies) {
            if (pos.equals(o)) return o;
        }

        Point  meilleure     = null;
        double meilleurRatio = -1.0;

        for (Point o : analyser.oliveraies) {
            // #2 : ignorer les oliveraies actuellement occupées par un adversaire
            if (analyser.positionsAdversaires.contains(o)) continue;

            // Distance A* réelle (pas Manhattan : les obstacles faussent la distance)
            int dist = distance(plateau, pos, o);
            if (dist <= 0) continue;

            // #7 : gain total simulé depuis notre énergie actuelle
            int gainEspere = simulerGainOliveraie(energieActuelle);
            if (gainEspere <= 0) continue; // déjà pleine, inutile d'y aller

            // Ratio : énergie récupérée par pas parcouru
            double ratio = (double) gainEspere / dist;

            if (ratio > meilleurRatio) {
                meilleurRatio = ratio;
                meilleure     = o;
            }
        }
        return meilleure;
    }

    /**
     * Simule le gain total d'énergie obtenu en entrant dans une oliveraie
     * avec l'énergie actuelle, en suivant la séquence 10/20/60/20/10.
     * S'arrête à 100 d'énergie (plafond).
     */
    private int simulerGainOliveraie(int energieActuelle) {
        int energieSim = energieActuelle;
        int gainTotal  = 0;
        for (int gain : GAIN_OLIVERAIE) {
            if (energieSim >= 100) break;
            int gainReel = Math.min(gain, 100 - energieSim);
            gainTotal   += gainReel;
            energieSim  += gainReel;
        }
        return gainTotal;
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
     * Durée optimale en oliveraie selon la séquence 10/20/60/20/10.
     *
     * #12 : valeurEnergie intègre le nombre de moulins possédés.
     * Raisonnement : avec N moulins, chaque point d'énergie récupéré permet
     * de produire N bouteilles par tour supplémentaire.
     * → valeurEnergie = toursRestants × max(1, nbMoulins) / 100
     *
     * Avec 0 moulin : max(1, 0) = 1 → comportement identique à avant.
     * Avec 3 moulins : l'énergie vaut 3× plus → on repart plus tôt.
     */
    private int calculerToursOptimaux(int energieActuelle, int nbMoulins, int toursRestants) {
        // #12 : pondération par le stock de moulins
        double valeurEnergie = (toursRestants * Math.max(1, nbMoulins)) / 100.0;
        int    energieSim    = energieActuelle;
        int    toursRester   = 0;

        for (int gain : GAIN_OLIVERAIE) {
            if (energieSim >= 100) break;
            int    gainReel = Math.min(gain, 100 - energieSim);
            double benefice = gainReel * valeurEnergie;
            double cout     = nbMoulins;

            // On reste si le bénéfice dépasse le coût, ou si énergie très basse
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

    // =========================================================================
    //  NAVIGATION
    // =========================================================================

    /**
     * Premier pas A* vers la cible avec évitement des adversaires.
     *
     * #9 — Détection de couloir :
     *   Si la case actuelle a ≤ SEUIL_COULOIR cases libres adjacentes,
     *   on est dans un passage étroit. Le blocage des adjacences adverses
     *   provoquerait une oscillation → on passe directement au chemin brut.
     *
     * #8 — 3 niveaux d'évitement progressif :
     *   Niveau 1 : positions adversaires + leurs 4 cases adjacentes bloquées
     *   Niveau 2 : uniquement les positions des adversaires bloquées
     *   Niveau 3 : chemin brut sans aucun évitement (dernier recours)
     */
    private Action allerVers(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null) return null;
        if (depart.equals(cible))            return Action.RIEN;

        ArrayList<Noeud> chemin;

        // #9 : couloir détecté → chemin brut directement pour éviter l'oscillation
        if (estDansCouloir(plateau, depart)) {
            chemin = plateau.donneCheminEntre(depart, cible);
        } else {
            // Niveau 1 : évitement complet (positions + adjacences)
            chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                    depart, cible, construireObstaclesComplets(plateau));

            // Niveau 2 : évitement partiel (positions seulement)
            if (chemin == null || chemin.isEmpty()) {
                chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                        depart, cible, construireObstaclesPositions());
            }

            // Niveau 3 : chemin brut
            if (chemin == null || chemin.isEmpty()) {
                chemin = plateau.donneCheminEntre(depart, cible);
            }
        }

        if (chemin == null || chemin.isEmpty()) return null;
        return directionVers(depart, chemin.get(0).getX(), chemin.get(0).getY());
    }

    /**
     * #9 : Détecte un couloir en comptant les cases libres adjacentes.
     * Un couloir = ≤ SEUIL_COULOIR cases traversables autour de nous.
     * Dans ce cas, éviter les adjacences adverses bloquerait notre unique sortie.
     */
    private boolean estDansCouloir(Plateau plateau, Point pos) {
        int[][] dirs  = {{0,1},{0,-1},{1,0},{-1,0}};
        int     libres = 0;
        for (int[] d : dirs) {
            int nx = pos.x + d[0], ny = pos.y + d[1];
            if (!plateau.coordonneeValide(nx, ny)) continue;
            int contenu = plateau.donneContenuCelluleSansJoueur(nx, ny);
            if (!Plateau.contientUneZoneInfranchissable(contenu)) libres++;
        }
        return libres <= SEUIL_COULOIR;
    }

    /**
     * #8 — Niveau 1 : positions des adversaires + leurs 4 cases adjacentes.
     * Garantit qu'on ne longe jamais un adversaire (déclencheur de manille).
     */
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

    /**
     * #8 — Niveau 2 : uniquement les positions directes des adversaires.
     * Fallback si le niveau 1 bloque tous les chemins possibles.
     */
    private List<Noeud> construireObstaclesPositions() {
        List<Noeud> obstacles = new ArrayList<>();
        for (Point p : analyser.positionsAdversaires) {
            obstacles.add(new Noeud(p.x, p.y));
        }
        return obstacles;
    }

    // =========================================================================
    //  UTILITAIRES
    // =========================================================================

    /**
     * Distance A* réelle entre deux points.
     * AEtoile.donneChemin() exclut le nœud de départ → chemin.size() = nb de pas.
     * Retourne 0 si depart == arrivee, -1 si inaccessible.
     */
    private int distance(Plateau plateau, Point depart, Point arrivee) {
        if (plateau == null || depart == null || arrivee == null) return -1;
        if (depart.equals(arrivee)) return 0;
        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, arrivee);
        return (chemin == null) ? -1 : chemin.size();
    }

    private int distanceManhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    private int compteMoulinsProches(Point centre) {
        int count = 0;
        for (Point m : analyser.moulinsLibres) {
            if (!m.equals(centre) && distanceManhattan(centre, m) <= RAYON_CLUSTER) count++;
        }
        for (Point m : analyser.moulinsAdverses) {
            if (!m.equals(centre) && distanceManhattan(centre, m) <= RAYON_CLUSTER) count++;
        }
        return count;
    }

    private boolean adversaireProcheDeCase(Point cible) {
        for (Point p : analyser.positionsAdversaires) {
            if (distanceManhattan(p, cible) <= RAYON_DANGER) return true;
        }
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