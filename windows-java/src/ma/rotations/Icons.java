package ma.rotations;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.util.*;

/** Icônes (mêmes dessins que la tablette), nettes aussi sur les écrans à haute résolution. */
public final class Icons {
    private Icons() { }
    private static final Map<String, ImageIcon> CACHE = new HashMap<>();

    static Image load(String name, int size) {
        try (var in = Icons.class.getResourceAsStream("icons/" + name + "_" + size + ".png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) { return null; }
    }

    /** Icône de taille logique « size » (16, 20, 24) avec variantes 1,5x et 2x. */
    public static ImageIcon get(String name, int size) {
        return CACHE.computeIfAbsent(name + size, k -> {
            List<Image> v = new ArrayList<>();
            for (int s : new int[]{size, size * 3 / 2, size * 2}) { Image im = load(name, s); if (im != null) v.add(im); }
            if (v.isEmpty()) return new ImageIcon();
            return new ImageIcon(v.size() == 1 ? v.get(0) : new BaseMultiResolutionImage(v.toArray(new Image[0])));
        });
    }
    public static ImageIcon get(String name) { return get(name, 20); }

    public static List<Image> appIcons() {
        List<Image> l = new ArrayList<>();
        for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) { Image im = load("app", s); if (im != null) l.add(im); }
        return l;
    }
}
