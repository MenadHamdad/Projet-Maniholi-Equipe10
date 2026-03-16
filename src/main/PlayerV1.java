import jeu.Joueur;
import jeu.Plateau;

public class PlayerV1 extends Joueur {

    public PlayerV1(String sonNom) {
        super(sonNom);
    }

    @Override
    public Action faitUneAction(Plateau etatDuJeu) {
        return super.faitUneAction(etatDuJeu);
    }
}
