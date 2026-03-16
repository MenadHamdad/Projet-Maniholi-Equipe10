/**
 * Exemple d'utilisation du JAR Maniholi.jar
 * Version Maniholi 2026 - 0.1
 */
import java.awt.Point;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;

import gui.FenetreDeJeu;
import jeu.Joueur;
import jeu.MaitreDuJeu;
import jeu.Plateau;
import jeu.aetoile.Noeud;

import static config.ConfigurationLog.UNITES_DE_RESSOURCAGE;
import static util.Outils.pointToString;
import static config.ConfigurationLog.UNITES_DE_PRODUCTION;

/**
 * Lancement du jeu en mode local.
 *
 * Cette classe permet de lancer une partie pendant la mise au
 * point de la stratÃ©gie d'un joueur.
 *
 * <p>Vous pouvez modifier les valeurs des constantes pour
 * paramÃ©trer :
 * <ul>
 *  <li>l'apparition du logo au lancement d'une partie</li>
 * 	<li>le plateau utilisÃ© (alÃ©atoire ou prÃ©dÃ©fini)</li>
 * 	<li>les types de joueurs qui s'affrontent</li>
 * 	<li>le fichier de log</li>
 * </ul>
 * </p>
 *
 * @author ClÃ©ment et Lucile
 */
public class Launcheur {
    /**
     *  Option de lancement du jeu : avec ou sans logo.
     *
     *  Vous pouvez positionner sa valeur Ã  false, si vous
     *  ne voulez plus voir s'afficher le logo au lancement.
     */
    public static final boolean LOGO_ACTIF = true;

    /**
     * Rayon autour de la case cliquÃ©e pour rechercher et afficher dans la console
     * les Ã©lÃ©ments (unitÃ©s de ressource, unitÃ©s  de production ou joueurs) proches
     *
     * <p>Modifier cette valeur change uniquement la zone d'observation autour de la cible :
     * On regarde RAYON cases Ã  droite, Ã  gauche, en haut et en bas autour de la case cliquÃ©e,
     * soit dans un carrÃ© de (2 * RAYON + 1) cases de cÃ´tÃ© autour de la case cliquÃ©e.</p>
     *
     * <p>Exemples : Avec RAYON = 2, on regarde 2 cases Ã  droite, Ã  gauche, en haut et en bas
     * autour de la case cliquÃ©e, c'est-Ã  dire dans un carrÃ© de 5 cases de cÃ´tÃ© autour de la case
     * cliquÃ©e. Avec RAYON = 0, on ne regarde que la case cliquÃ©e. Avec RAYON = 1, on regarde
     * dans un carrÃ© de 3 cases de cÃ´tÃ© autour de la case cliquÃ©e.</p>
     *
     * @see #gestionClicsPlateau(FenetreDeJeu, Plateau)
     */
    public static final int RAYON = 2;

    /**
     * ParamÃ¨tres de configuration du fichier de log utilisÃ© par {@link MaitreDuJeu}.
     *
     * <p><b>Important :</b> sous Windows, vous devez modifier au minimum
     * {@link #LOG_REPERTOIRE} car le rÃ©pertoire "/tmp" n'existe pas.
     * Vous pouvez par exemple utiliser "C:/temp" ou un rÃ©pertoire de votre choix.</p>
     *
     * <p>{@link #LOG_FORMATEUR} dÃ©finit le format de la date utilisÃ©e
     * dans le nom du fichier de log :</p>
     *
     * <ul>
     *   <li><code>"yyyy-MM-dd"</code> : crÃ©ation d'un seul fichier de log par jour,
     *   remplacÃ© Ã  chaque nouvelle exÃ©cution du mÃªme jour, par exemple :
     *   maniholi-2026-03-16.log</li>
     *
     *   <li><code>"yyyy-MM-dd_HH-mm-ss"</code> : crÃ©ation d'un fichier de log
     *   diffÃ©rent Ã  chaque exÃ©cution du programme, grÃ¢ce Ã  lâ€™ajout dans le nom de lâ€™heure,
     *   des minutes et des secondes, par exemple : maniholi-2026-03-16_14-32-05.log. Cette
     *   option est prÃ©fÃ©rable si vous lancez plusieurs parties le mÃªme jour.</li>
     * </ul>
     */
    public static final String LOG_REPERTOIRE = "/tmp";
    public static final String LOG_PREFIXE = "maniholi";
    //public static final DateTimeFormatter LOG_FORMATEUR = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter LOG_FORMATEUR = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    public static final String FICHIER_DE_LOG = LOG_REPERTOIRE + "/"
            + LOG_PREFIXE + "-"
            + LocalDateTime.now().format(LOG_FORMATEUR)
            + ".log";

    /**
     * Lancement du jeu.
     *
     * Le jeu peut Ãªtre configurÃ© en prÃ©cisant :
     * <ul><li>le {@link Plateau} qui doit Ãªtre utilisÃ© : vous pouvez utiliser un plateau alÃ©atoire ou prÃ©dÃ©fini</li>
     * <li>les 4 types de {@link Joueur} qui s'affrontent</li>
     * <li>le fichier de log de {@link FenetreDeJeu} utilisÃ© par le {@link MaitreDuJeu}</li>
     * </ul>
     *
     * @param args arguments de la ligne de commande (non utilisÃ©s)
     */
    public static void main(String[] args) {

        /* ************************************************
         * --- GÃ©nÃ©ration du plateau ---                  *
         * Vous pouvez utiliser le plateau de votre choix *
         * ************************************************/
        //final Plateau plateau = Plateau.generePlateauTournoi(); // un plateau alÃ©atoire de tournoi
        //final Plateau plateau = Plateau.generePlateauAleatoire(100, 10, 8, 8, 20); // un plateau alÃ©atoire 20x20 pour une partie de 100 tours, 32 moulins, 32 oliveraies et 80 rochers
        //final Plateau plateau = Plateau.generePlateauAleatoire(1200, 10, 8, 8, 20); // un plateau alÃ©atoire 20x20 pour une partie de 1200 tours, 32 moulins, 32 oliveraies et 80 rochers
        //final Plateau plateau = new Plateau(300, Plateau.TEXTE_PLATEAU_ANORMAL_8x8_1); // un plateau prÃ©dÃ©fini 8x8 de test
        final Plateau plateau = new Plateau(1000, Plateau.TEXTE_PLATEAU_ENONCE); // le plateau 20x20 de l'Ã©noncÃ© pour une partie de 1000 tours

        /* ***********************************
         * --- CrÃ©ation du maitre de jeu --- *
         * ***********************************/
        final MaitreDuJeu jeu = new MaitreDuJeu(plateau);


        /* ***************************************
         * --- CrÃ©ation de la fenÃªtre de jeu --- *
         * ***************************************/
        final FenetreDeJeu fenetre = new FenetreDeJeu(jeu, true, LOGO_ACTIF);

        /* **************************************************************
         * --- Ajout des 4 joueurs dans le jeu ---                      *
         * Vous pouvez dÃ©finir de 1 Ã  4 joueurs. Par dÃ©faut les joueurs *
         * non ajoutÃ©s explicitement sont ajoutÃ©s comme des instances   *
         * de Joueur, c'est-Ã -dire des joueurs Ã  dÃ©placement alÃ©atoire. *
         * **************************************************************/
        jeu.metJoueurEnPosition(0, new PlayerV1("1")); // un joueur spÃ©cifique
        jeu.metJoueurEnPosition(1, new PlayerV2("2")); // un joueur spÃ©cifique
        //jeu.metJoueurEnPosition(1, new JoueurHumain("Panisse",fenetre)); // un joueur humain
        //jeu.metJoueurEnPosition(2, new Joueur("Escartefigue")); // un joueur Ã  dÃ©placement alÃ©atoire
        //jeu.metJoueurEnPosition(3, new Joueur("M. Brun")); // un joueur Ã  dÃ©placement alÃ©atoire

        /* ***************************************************************
         * --- DÃ©finition du fichier log ---                             *
         * Vous devez redÃ©finir la variable fenetre.log pour envoyer les *
         * log de la partie dans un fichier texte                        *
         * ***************************************************************/
        fenetre.log = new java.io.File( FICHIER_DE_LOG); // envoi des log dans FICHIER_DE_LOG
        //fenetre.log = null; // si null, aucun envoi de log

        /* *************************************************
         * --- Gestion des clics souris sur le plateau --- *
         * *************************************************/
        gestionClicsPlateau(fenetre, plateau);
    }

    /**
     * GÃ¨re les clics souris sur le plateau.
     *
     * <p>La mÃ©thode rÃ©alise deux actions distinctes lorsqu'on clique sur une case :
     *
     * <ol>
     *   <li><b>Affichage graphique du plus court chemin</b> :
     *       <br>Le chemin le plus court entre le joueur courant et la case cliquÃ©e
     *       est calculÃ© avec {@link Plateau#donneCheminEntre(Point, Point)} et
     *       affichÃ© sur le plateau par surlignage des cases en jaune.
     *       <br><i>Cette partie nâ€™est pas influencÃ©e par la valeur de {@link #RAYON}.</i>
     *   </li>
     *   <li><b>Affichage textuel des Ã©lÃ©ments autour du clic</b> :
     *       <br>La mÃ©thode {@link Plateau#cherche(Point, int, int)} est utilisÃ©e pour
     *       lister dans la console tous les Ã©lÃ©ments (unitÃ©s de ressourÃ§age, unitÃ©s de production,
     *       joueurs) prÃ©sents dans un rayon de {@link #RAYON} cases autour de la case cliquÃ©e.
     *       <br><i>Changer la valeur de {@link #RAYON} augmente ou rÃ©duit la zone observÃ©e .</i>
     *   </li>
     * </ol>
     *
     * @param fenetre la fenÃªtre de jeu utilisÃ©e pour l'affichage graphique
     * @param plateau le plateau courant
     * @see #RAYON
     */
    private static void gestionClicsPlateau(FenetreDeJeu fenetre, Plateau plateau) {
        // Ajout d'un Ã©couteur des clics souris sur le plateau pour visualiser les chemins
        // On peut donc cliquer sur une case de la carte pendant la partie !
        fenetre.setMouseClickListener((int x, int y, int bt) -> {
            System.out.println("\n>>>>>>>>>>>>>>On a cliquÃ© la cellule " + x + "," + y);

            // Ne fonctionne que pour une partie en cours
            final Joueur j = plateau.donneJoueur(plateau.donneJoueurCourant());
            System.out.println("*CHEMIN\n   Depart=" + pointToString(j.donnePosition()));
            System.out.println("   ArrivÃ©e=" + pointToString(new Point(x, y)));

            System.out.println("*AUTOUR DE " + pointToString(new Point(x, y)) + " dans un rayon de " + RAYON);
            afficheResultatRecherche(plateau.cherche(new Point(x, y), RAYON, Plateau.CHERCHE_TOUT));
            final ArrayList<Noeud> a = plateau.donneCheminEntre(j.donnePosition(), new Point(x, y));
            fenetre.afficheCheminAEtoile(a);
        });

        // Affichage de la fenÃªtre
        java.awt.EventQueue.invokeLater(() -> fenetre.setVisible(true));
    }

    /**
     * Formate et affiche le rÃ©sultat de la mÃ©thode {@link Plateau#cherche(Point, int, int)}.
     *
     * @param hashMap rÃ©sultat de la recherche Ã  afficher
     */
    private static void afficheResultatRecherche(HashMap<Integer, ArrayList<Point>> hashMap) {
        hashMap.keySet().stream().map((k) -> {
            System.out.print("   ");
            System.out.print(k==1?UNITES_DE_RESSOURCAGE:k==2?UNITES_DE_PRODUCTION:"JOUEURS");
            System.out.print(" : ");
            return k;
        }).map((k) -> {
            hashMap.get(k).forEach((p) ->
                    System.out.print(pointToString(p)));//System.out.print("(" + p.x + "," + p.y + ") "));
            return k;
        }).forEachOrdered((_item) -> System.out.println());
    }
}