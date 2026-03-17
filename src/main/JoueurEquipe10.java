import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JoueurEquipe10 extends Joueur {

    private static final int COUT_CAPTURE = 20;
    private static final int ENERGIE_MIN_CAPTURE = COUT_CAPTURE + 1; // 21

    private static final int SEUIL_CRITIQUE_BASE = 20;
    private static final int SEUIL_PREVENTIF = 70;
    private static final int MARGE_SECURITE = 10;
    private static final int BUFFER_OLIVERAIE = 8;
    private static final int BUFFER_RETOUR = 6;

    private static final int FIN_RUSH = 80;
    private static final int SEUIL_FIN_PARTIE = 50;

    private static final int RAYON_CLUSTER = 5;
    private static final double POIDS_CLUSTER = 2.5;
    private static final double POIDS_CLUSTER_RUSH = 1.0;
    private static final double BONUS_ENNEMI = 1.4;
    private static final double MALUS_CONTESTE = 0.6;
    private static final int RAYON_DANGER = 3;

    private static final int PROFONDEUR_LOOKAHEAD = 3;
    private static final double DEPRECIATION_LOOKAHEAD = 0.5;

    private static final int[] GAIN_OLIVERAIE = {10, 20, 60, 20, 10};
    private static final int SEUIL_COULOIR = 2;

    private static final int NOMBRE_TOUR_BLOCAGE = 5;

    // ── État interne ──────────────────────────────────────────────────────────
    private boolean enTrainDeRecolter = false;
    private int toursEnOliveraie = 0;
    private int toursOptimaux = 0;
    private Point cibleOliveraie = null;
    private boolean vientDeTerminerRecolte = false;
    private Point positionPrecedente = null;
    private int toursImmobile = 0;

    private final Map<Long, Integer> cacheDistances = new HashMap<>(256);
    private final PlateauAnalyser analyser;

    public JoueurEquipe10(String sonNom) {
        super(sonNom);
        this.analyser = new PlateauAnalyser();
    }

    @Override
    public void debutDePartie(int rang) {
        super.debutDePartie(rang);
        enTrainDeRecolter = false;
        toursEnOliveraie = 0;
        toursOptimaux = 0;
        cibleOliveraie = null;
        vientDeTerminerRecolte = false;
        positionPrecedente = null;
        toursImmobile = 0;
        cacheDistances.clear();
    }

    @Override
    public Action faitUneAction(Plateau plateau) {
        // Réinitialisation début de tour
        cacheDistances.clear();
        vientDeTerminerRecolte = false;
        analyser.analysePlateau(plateau, this);

        final Point pos = donnePosition();
        final int energie = donneRessources();
        final int tourCourant = plateau.donneTourCourant();
        final int toursRestants = plateau.donneNombreDeTours() - tourCourant;
        final int nbMoulins = analyser.nombreMoulinsNous;
        final boolean finPartie = toursRestants <= SEUIL_FIN_PARTIE;
        final boolean phaseRush = tourCourant <= FIN_RUSH;

        // On compare la position actuelle avec celle du tour précédent.
        if (!enTrainDeRecolter) {
            if (positionPrecedente.equals(pos)) {
                toursImmobile++;
            } else {
                toursImmobile = 0;
            }
        }
        positionPrecedente = new Point(pos.x, pos.y);

        final int distMinOliveraie = distanceMinOliveraie(pos);

        // On garde toujours assez d'énergie pour rejoindre une oliveraie
        final int seuilCritique = Math.max(SEUIL_CRITIQUE_BASE,
                distMinOliveraie + BUFFER_OLIVERAIE);

        // ── Machine à états : récolte en oliveraie ────────────────────────────
        // INVARIANT : une fois enTrainDeRecolter=true, on reste RIEN jusqu'à
        // toursOptimaux atteint ou énergie pleine. On ne sort jamais prématurément.
        if (enTrainDeRecolter) {
            if (estSurOliveraie(plateau, pos)) {
                toursEnOliveraie++;
                if (energie >= 100 || toursEnOliveraie >= toursOptimaux) {
                    terminerRecolte(); // vientDeTerminerRecolte = true ce tour
                    // On continue vers les priorités suivantes pour ne pas perdre ce tour
                } else {
                    return Action.RIEN; // Rester dans l'oliveraie
                }
            } else {
                terminerRecolte(); // Sécurité : déplacé hors oliveraie
            }
        }

        // ── Priorité 0 : énergie nulle ───────────────────────
        if (energie == 0) {
            cibleOliveraie = null;
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        // ── Mode fin de partie ────────────────────────────────────────────────
        if (finPartie) {
            cibleOliveraie = null;
            return faitUneActionFinDePartie(plateau, pos, energie, toursRestants, nbMoulins);
        }

        // ─── MODE NORMAL / RUSH ───────────────────────────────────────────────

        // Priorité 1 : déjà sur oliveraie et énergie basse → commencer récolte
        // CORRECTION BUG : on ne redémarre PAS si on vient juste de terminer
        // (vientDeTerminerRecolte = true), ce qui évite la boucle infinie.
        if (!vientDeTerminerRecolte
                && estSurOliveraie(plateau, pos)
                && energie < SEUIL_PREVENTIF) {
            cibleOliveraie = null;
            demarrerRecolte(energie, nbMoulins, toursRestants);
            return Action.RIEN;
        }

        // Priorité 2 : énergie critique → recharger
        if (energie <= seuilCritique) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        // Priorité 3 : capturer le meilleur moulin
        Point cible = phaseRush
                ? trouveMeilleurMoulinPendantLeRush(plateau, pos, energie, toursRestants)
                : trouveMeilleurMoulin(plateau, pos, energie, toursRestants, false);

        if (cible != null) {
            cibleOliveraie = null;
            Action a = allerVers(plateau, pos, cible);
            if (a != null) return a;
        }

        // Priorité 4 : recharge préventive
        if (energie < SEUIL_PREVENTIF) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        return Action.RIEN;
    }

    // Détermine combien de tours il est rentable de rester dans une oliveraie.
    // Le calcul compare :
    // - la valeur stratégique de l'énergie (basée sur moulins + tours restants)
    // - le coût d'un tour immobile (perte potentielle de capture de moulin).
    // On arrête la récolte quand le gain devient moins intéressant.
    private int calculerToursOptimaux(int energieActuelle, int nbMoulins, int toursRestants) {
        double valeurEnergie = (toursRestants * (double) Math.max(1, nbMoulins)) / 100.0;
        int energieSim = energieActuelle;
        int toursRester = 0;

        for (int gain : GAIN_OLIVERAIE) {
            if (energieSim >= 100) break;
            int gainReel = Math.min(gain, 100 - energieSim);
            double benefice = gainReel * valeurEnergie;
            double cout = Math.max(1, nbMoulins);
            if (benefice >= cout || energieSim < 30) {
                toursRester++;
                energieSim += gainReel;
            } else {
                break;
            }
        }
        return Math.max(1, toursRester);
    }

    /**
     * Termine la récolte et signale via vientDeTerminerRecolte=true que ce tour
     * on vient de quitter l'oliveraie
     */
    private void terminerRecolte() {
        enTrainDeRecolter = false;
        toursEnOliveraie = 0;
        toursOptimaux = 0;
        vientDeTerminerRecolte = true;
    }

    private void demarrerRecolte(int energie, int nbMoulins, int toursRestants) {
        toursOptimaux = calculerToursOptimaux(energie, nbMoulins, toursRestants);
        enTrainDeRecolter = true;
        toursEnOliveraie = 1;
    }

    private Action allerVersOliveraieOuRester(Plateau plateau, Point actualPosition, int energie,
                                              int nbMoulins, int toursRestants) {
        if (cibleOliveraie != null && analyser.positionsAdversaires.contains(cibleOliveraie)) {
            cibleOliveraie = null;
        }

        if (cibleOliveraie == null) {
            cibleOliveraie = trouveOliveraieOptimale(plateau, actualPosition, energie);
        }
        if (cibleOliveraie == null) {
            return Action.RIEN;
        }

        // Si on ce trouve deja sur l'oliveraie optimale
        if (actualPosition.equals(cibleOliveraie)) {
            cibleOliveraie = null;
            demarrerRecolte(energie, nbMoulins, toursRestants);
            return Action.RIEN;
        }

        Action action = allerVers(plateau, actualPosition, cibleOliveraie);
        return (action != null) ? action : Action.RIEN;
    }

    private int distanceMinOliveraie(Point actualPoint) {
        int distanceMin = 0;
        for (Point point : analyser.oliveraies) {
            if (analyser.positionsAdversaires.contains(point)) {
                continue;
            }
            if (actualPoint.equals(point)) {
                return 0;
            }
            int distanceManhattan = distanceManhattan(actualPoint, point);
            if (distanceManhattan < distanceMin) distanceMin = distanceManhattan;
        }
        return distanceMin;
    }

    private int distanceManhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    private Point trouveMeilleurMoulinPendantLeRush(Plateau plateau, Point actualPosition, int energie, int toursRestants) {
        Point meilleur = null;
        double meilleurScore = Double.NEGATIVE_INFINITY;

        for (Point moulinCible : analyser.moulinsLibres) {
            int dist = distanceCachee(plateau, actualPosition, moulinCible);

            if (dist <= 0) {
                continue;
            }
            if (energie < dist + ENERGIE_MIN_CAPTURE) {
                continue;
            }
            if (!peutCapturerEtRentrer(energie, dist, moulinCible)) {
                continue;
            }

            double toursGain = toursRestants - dist;
            if (toursGain <= 0) {
                continue;
            }

            double score = toursGain / (dist + 1.0);
            score += compteMoulinsProches(moulinCible) * POIDS_CLUSTER_RUSH;

            if (score > meilleurScore) {
                meilleurScore = score;
                meilleur = moulinCible;
            }
        }
        return meilleur;
    }

    private int distanceCachee(Plateau plateau, Point depart, Point arrivee) {
        if (depart == null || arrivee == null) return -1;
        if (depart.equals(arrivee)) return 0;

        long cle = ((long) depart.x << 15)
                | ((long) depart.y << 10)
                | ((long) arrivee.x << 5)
                | (long) arrivee.y;

        Integer cached = cacheDistances.get(cle);
        if (cached != null) return cached;

        ArrayList<Noeud> chemin = plateau.donneCheminEntre(depart, arrivee);
        int dist = (chemin == null) ? -1 : chemin.size();
        cacheDistances.put(cle, dist);
        return dist;
    }

    // Vérifie qu'après la capture d'un moulin le joueur aura encore
    // assez d'énergie pour rejoindre une oliveraie.
    // Cela évite de capturer un moulin puis de tomber à 0 énergie
    // loin de toute source de recharge.
    private boolean peutCapturerEtRentrer(int energie, int distMoulin, Point moulin) {
        int energieApres = energie - distMoulin - COUT_CAPTURE;
        if (energieApres < 1) return false;

        int distOliv = Integer.MAX_VALUE;
        for (Point o : analyser.oliveraies) {
            if (analyser.positionsAdversaires.contains(o)) continue;
            int d = distanceManhattan(moulin, o);
            if (d < distOliv) distOliv = d;
        }
        if (distOliv == Integer.MAX_VALUE) return energieApres >= 1;
        return energieApres > distOliv + BUFFER_RETOUR;
    }

    private Action faitUneActionFinDePartie(Plateau plateau, Point actualPoint, int energie,
                                            int toursRestants, int nbMoulins) {
        if (energie < ENERGIE_MIN_CAPTURE) {
            return allerVersOliveraieOuRester(plateau, actualPoint, energie, nbMoulins, toursRestants);
        }
        Point cible = trouveMeilleurMoulin(plateau, actualPoint, energie, toursRestants, true);
        if (cible != null) {
            Action a = allerVers(plateau, actualPoint, cible);
            if (a != null) return a;
        }
        return Action.RIEN;
    }

    private Point trouveMeilleurMoulin(Plateau plateau, Point actualPoint, int energie, int toursRestants, boolean modeAgressif) {
        final int margeMin = modeAgressif ? ENERGIE_MIN_CAPTURE : ENERGIE_MIN_CAPTURE + MARGE_SECURITE;

        Point meilleurPoint = null;
        double meilleurScore = Double.NEGATIVE_INFINITY;

        List<Point> candidats = new ArrayList<>(analyser.moulinsLibres);
        candidats.addAll(analyser.moulinsAdverses);

        for (Point cible : candidats) {
            int dist = distanceCachee(plateau, actualPoint, cible);

            // On regarde si on a assez d'énergie pour y acceder
            if (energie < dist + margeMin) {
                continue;
            }

            if (!peutCapturerEtRentrer(energie, dist, cible)) {
                continue;
            }

            // On regarde s'il nous reste assez de tours pour acceder au moulin
            int toursGain = toursRestants - dist;
            if (toursGain <= 0) {
                continue;
            }

            boolean isMoulinEnnemi = analyser.moulinsAdverses.contains(cible);

            double score = toursGain;
            score += compteMoulinsProches(cible) * POIDS_CLUSTER;
            score += scoreLookahead(plateau, cible,
                    energie - dist - COUT_CAPTURE,
                    toursRestants - dist,
                    PROFONDEUR_LOOKAHEAD, DEPRECIATION_LOOKAHEAD, modeAgressif);

            if (isMoulinEnnemi) {
                score *= BONUS_ENNEMI;
            }
            if (adversaireProcheDeCase(cible)) {
                score *= MALUS_CONTESTE;
            }

            score /= (dist + 1.0);

            if (score > meilleurScore) {
                meilleurScore = score;
                meilleurPoint = cible;
            }
        }
        return meilleurPoint;
    }

    // Simulation récursive de captures futures.
    // On estime la valeur d'un moulin en regardant les moulins
    // potentiellement capturables ensuite.
    // Chaque niveau de profondeur est déprécié pour éviter
    // de surestimer les gains trop lointains.
    private double scoreLookahead(Plateau plateau, Point depart, int energieApres,
                                  int toursApres, int profondeur, double poids,
                                  boolean modeAgressif) {

        if (profondeur == 0 || energieApres < ENERGIE_MIN_CAPTURE || toursApres <= 0) {
            return 0;
        }

        final int margeMin = modeAgressif
                ? ENERGIE_MIN_CAPTURE
                : ENERGIE_MIN_CAPTURE + MARGE_SECURITE;

        Point bestCible = null;
        int bestDist = 0;
        double best = 0;

        List<Point> candidats = new ArrayList<>(analyser.moulinsLibres);
        candidats.addAll(analyser.moulinsAdverses);

        for (Point cible : candidats) {

            if (cible.equals(depart)) {
                continue;
            }
            int dist = distanceCachee(plateau, depart, cible);

            // On regarde si on a assez d'énergie pour y acceder
            if (energieApres < dist + margeMin) {
                continue;
            }

            // On regarde s'il nous reste assez de tours pour acceder au moulin
            double toursGain = toursApres - dist;
            if (toursGain <= 0) {
                continue;
            }

            boolean isMoulinEnnemi = analyser.moulinsAdverses.contains(cible);
            double score = toursGain / (dist + 1.0);

            if (isMoulinEnnemi) {
                score *= BONUS_ENNEMI;
            }

            if (score > best) {
                best = score;
                bestCible = cible;
                bestDist = dist;
            }
        }

        if (bestCible == null) {
            return 0;
        }

        double scoreNiveau = best * poids;

        scoreNiveau += scoreLookahead(plateau
                , bestCible,
                energieApres - bestDist - COUT_CAPTURE,
                toursApres - bestDist,
                profondeur - 1, poids * DEPRECIATION_LOOKAHEAD, modeAgressif);

        return scoreNiveau;
    }


    /**
     * Sélectionne la meilleure oliveraie libre selon gain/distance.
     */
    private Point trouveOliveraieOptimale(Plateau plateau, Point actualPosition, int energieActuelle) {
        if (analyser.oliveraies.isEmpty()) return null;

        // Si on est dessus ET qu'on n'est pas en train de terminer une récolte on reste sans rien faire
        if (!vientDeTerminerRecolte) {
            for (Point point : analyser.oliveraies) {
                if (actualPosition.equals(point)) {
                    return point;
                }
            }
        }

        int gain = simulerGainOliveraie(energieActuelle);
        Point meilleure = null;
        double bestRatio = -1.0;

        for (Point point : analyser.oliveraies) {

            if (analyser.positionsAdversaires.contains(point)) {
                continue;
            }

            // Exclure l'oliveraie courante si on vient de terminer dedans
            if (vientDeTerminerRecolte && actualPosition.equals(point)) {
                continue;
            }

            double distanceCachee = distanceCachee(plateau, actualPosition, point);
            if (distanceCachee <= 0 || gain <= 0) continue;

            double ratio = gain / distanceCachee;
            if (ratio > bestRatio) {
                bestRatio = ratio;
                meilleure = point;
            }
        }

        // si aucune autre oliveraie n'est disponible,
        // autoriser celle où on se trouve
        if (meilleure == null && vientDeTerminerRecolte) {
            for (Point point : analyser.oliveraies) {
                if (actualPosition.equals(point)) return point;
            }
        }

        return meilleure;
    }

    private int simulerGainOliveraie(int energieActuelle) {
        int simulation = energieActuelle, total = 0;
        for (int gain : GAIN_OLIVERAIE) {
            if (simulation >= 100){
                break;
            }
            int result = Math.min(gain, 100 - simulation);
            total += result;
            simulation += result;
        }
        return total;
    }


    private Action allerVers(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null) return null;
        if (depart.equals(cible)) return Action.RIEN;

        ArrayList<Noeud> chemin;

        // Stratégie de pathfinding en plusieurs niveaux :
        // 1) éviter les adversaires + leurs cases adjacentes
        // 2) éviter seulement les positions adverses
        // 3) fallback : chemin brut si aucun chemin sûr trouvé
        boolean forcerBrut = estSurOliveraie(plateau, depart)
                || toursImmobile >= NOMBRE_TOUR_BLOCAGE
                || estDansCouloir(plateau, depart);

        if (forcerBrut) {
            chemin = plateau.donneCheminEntre(depart, cible);
        } else {
            // Niveau 1 : évitement complet (positions + adjacences)
            chemin = plateau.donneCheminAvecObstaclesSupplementaires(
                    depart, cible, construireObstaclesComplets(plateau));

            // Niveau 2 : positions adversaires seulement
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

    private boolean estSurOliveraie(Plateau plateau, Point pos) {
        if (pos == null) return false;
        return Plateau.contientUneUniteDeRessourcage(
                plateau.donneContenuCelluleSansJoueur(pos.x, pos.y));
    }

    private boolean estDansCouloir(Plateau plateau, Point pos) {
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        int libres = 0;
        for (int[] d : dirs) {
            int nx = pos.x + d[0], ny = pos.y + d[1];
            if (!plateau.coordonneeValide(nx, ny)) continue;
            if (!Plateau.contientUneZoneInfranchissable(
                    plateau.donneContenuCelluleSansJoueur(nx, ny))) libres++;
        }
        return libres <= SEUIL_COULOIR;
    }

    // On considère les adversaires comme des obstacles dynamiques,
    // ainsi que les cases adjacentes pour éviter les collisions
    // ou les confrontations directes.
    private List<Noeud> construireObstaclesComplets(Plateau plateau) {
        List<Noeud> obstacles = new ArrayList<>();
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
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