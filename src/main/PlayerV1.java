import helper.PlateauAnalyser;
import jeu.Joueur;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PlayerV1 extends Joueur {

    private static final int COUT_CAPTURE = 20;

    private static final int ENERGIE_MIN_CAPTURE = COUT_CAPTURE + 1; // 21

    private static final int SEUIL_CRITIQUE = 20;
    private static final int SEUIL_PREVENTIF = 70;
    private static final int MARGE_SECURITE = 10;

    // ── Fin de partie ─────────────────────────────────────────────────────────
    /**
     * Sous ce seuil, MARGE_SECURITE est supprimée (mode agressif).
     */
    private static final int SEUIL_FIN_PARTIE = 50;

    // ── Scoring moulins ───────────────────────────────────────────────────────
    private static final int RAYON_CLUSTER = 5;
    private static final double POIDS_CLUSTER = 2.5;
    private static final double BONUS_ENNEMI = 1.4;
    private static final double MALUS_CONTESTE = 0.6;
    private static final int RAYON_DANGER = 3;

    // ── Lookahead 3 niveaux ───────────────────────────────────────────────────
    private static final int PROFONDEUR_LOOKAHEAD = 3;
    private static final double DEPRECIATION_LOOKAHEAD = 0.5;

    // todo revoir comment on utilise ca dans le code
    private static final int[] GAIN_OLIVERAIE = {10, 20, 60, 20, 10};

    private static final int SEUIL_COULOIR = 2;

    // ── État interne (réinitialisé via debutDePartie) ─────────────────────────
    private boolean enTrainDeRecolter = false;
    private int toursEnOliveraie = 0;
    private int toursOptimaux = 0;

    private final PlateauAnalyser analyser;

    // ─────────────────────────────────────────────────────────────────────────

    public PlayerV1(String sonNom) {
        super(sonNom);
        this.analyser = new PlateauAnalyser();
    }

    @Override
    public void debutDePartie(int rang) {
        super.debutDePartie(rang);
        enTrainDeRecolter = false;
        toursEnOliveraie = 0;
        toursOptimaux = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Action faitUneAction(Plateau plateau) {
        analyser.analysePlateau(plateau, this);

        Point pos = donnePosition();
        int energie = donneRessources();
        int tourCourant = plateau.donneTourCourant();
        int toursRestants = plateau.donneNombreDeTours() - tourCourant;
        int nbMoulins = analyser.nombreMoulinsNous;
        boolean finPartie = toursRestants <= SEUIL_FIN_PARTIE;

        // ── Récolte en oliveraie en cours ─────────────────────────────────────
        if (enTrainDeRecolter) {
            if (estSurOliveraie(plateau, pos)) {
                toursEnOliveraie++;
                if (energie >= 100 || toursEnOliveraie >= toursOptimaux) {
                    terminerRecolte();
                } else {
                    return Action.RIEN;
                }
            } else {
                terminerRecolte();
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
            Action a = allerVersUnePosition(plateau, pos, cible);
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

    private Action faitUneActionFinDePartie(Plateau plateau, Point pos, int energie,
                                            int toursRestants, int nbMoulins) {
        if (energie < ENERGIE_MIN_CAPTURE) {
            return allerVersOliveraieOuRester(plateau, pos, energie, nbMoulins, toursRestants);
        }

        Point cible = trouveMeilleurMoulin(plateau, pos, energie, toursRestants, true);
        if (cible != null) {
            Action a = allerVersUnePosition(plateau, pos, cible);
            return a;
        }

        return Action.RIEN;
    }

    private Point trouveMeilleurMoulin(Plateau plateau, Point pos, int energie,
                                       int toursRestants, boolean modeAgressif) {
        final int margeMin = modeAgressif
                ? ENERGIE_MIN_CAPTURE
                : ENERGIE_MIN_CAPTURE + MARGE_SECURITE;

        Point meilleur = null;
        double mScore = Double.NEGATIVE_INFINITY;

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

            if (ennemi) score *= BONUS_ENNEMI;
            if (adversaireProcheDeCase(cible)) score *= MALUS_CONTESTE;
            score /= (dist + 1.0);

            if (score > mScore) {
                mScore = score;
                meilleur = cible;
            }
        }
        return meilleur;
    }

    private double scoreLookahead(Plateau plateau, Point depart, int energieApres,
                                  int toursApres, int profondeur, double poids,
                                  boolean modeAgressif) {
        if (profondeur == 0 || energieApres < ENERGIE_MIN_CAPTURE || toursApres <= 0) return 0;

        final int margeMin = modeAgressif
                ? ENERGIE_MIN_CAPTURE
                : ENERGIE_MIN_CAPTURE + MARGE_SECURITE;

        Point bestCible = null;
        int bestDist = 0;
        double best = 0;

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
                best = s;
                bestCible = cible;
                bestDist = dist;
            }
        }

        if (bestCible == null) return 0;

        double scoreNiveau = best * poids;
        int energieSuivante = energieApres - bestDist - COUT_CAPTURE;
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
        Point oliveraiePosition = trouveOliveraieOptimale(plateau, pos, energie);

        if (pos.equals(oliveraiePosition)) {
            demarrerRecolte(energie, nbMoulins, toursRestants);
            return Action.RIEN;
        }
        Action a = allerVersUnePosition(plateau, pos, oliveraiePosition);
        return (a != null) ? a : Action.RIEN;
    }

    private Point trouveOliveraieOptimale(Plateau plateau, Point pos, int energieActuelle) {
        if (analyser.oliveraies.isEmpty()) return null;

        // Déjà dessus → retour immédiat
        for (Point o : analyser.oliveraies) {
            if (pos.equals(o)) return o;
        }

        Point meilleure = null;
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
                meilleure = o;
            }
        }
        return meilleure;
    }

    private int simulerGainOliveraie(int energieActuelle) {
        int energieSim = energieActuelle;
        int gainTotal = 0;
        for (int gain : GAIN_OLIVERAIE) {
            if (energieSim >= 100) break;
            int gainReel = Math.min(gain, 100 - energieSim);
            gainTotal += gainReel;
            energieSim += gainReel;
        }
        return gainTotal;
    }

    private void demarrerRecolte(int energie, int nbMoulins, int toursRestants) {
        toursOptimaux = calculerToursOptimaux(energie, nbMoulins, toursRestants);
        enTrainDeRecolter = true;
        toursEnOliveraie = 1;
    }

    private void terminerRecolte() {
        enTrainDeRecolter = false;
        toursEnOliveraie = 0;
        toursOptimaux = 0;
    }

    private int calculerToursOptimaux(int energieActuelle, int nbMoulins, int toursRestants) {
        // #12 : pondération par le stock de moulins
        double valeurEnergie = (toursRestants * Math.max(1, nbMoulins)) / 100.0;
        int energieSim = energieActuelle;
        int toursRester = 0;

        for (int gain : GAIN_OLIVERAIE) {
            if (energieSim >= 100) break;
            int gainReel = Math.min(gain, 100 - energieSim);
            double benefice = gainReel * valeurEnergie;
            double cout = nbMoulins;

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

    private Action allerVersUnePosition(Plateau plateau, Point depart, Point cible) {
        if (depart == null || cible == null) return null;
        if (depart.equals(cible)) return Action.RIEN;

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

    private boolean estDansCouloir(Plateau plateau, Point pos) {
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        int libres = 0;
        for (int[] d : dirs) {
            int nx = pos.x + d[0], ny = pos.y + d[1];
            if (!plateau.coordonneeValide(nx, ny)) continue;
            int contenu = plateau.donneContenuCelluleSansJoueur(nx, ny);
            if (!Plateau.contientUneZoneInfranchissable(contenu)) libres++;
        }
        return libres <= SEUIL_COULOIR;
    }

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
        List<Noeud> obstacles = new ArrayList<>();
        for (Point p : analyser.positionsAdversaires) {
            obstacles.add(new Noeud(p.x, p.y));
        }
        return obstacles;
    }

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