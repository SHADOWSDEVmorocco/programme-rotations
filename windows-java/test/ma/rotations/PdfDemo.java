package ma.rotations;
public class PdfDemo { public static void main(String[] a) throws Exception {
    Core.Program p = Core.defaultProgram(true);
    Pdf.write(java.nio.file.Paths.get(a[0]), p.aircraft, "Programme des rotations – test");
} }
