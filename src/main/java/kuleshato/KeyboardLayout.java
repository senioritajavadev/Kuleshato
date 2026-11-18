package kuleshato;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Текст, набранный не в той раскладке: «dct yfgbcfyj d tlbyjv cnbkt&» — это «все написано в едином стиле?».
 *
 * <p>Слово считается набранным латиницей в русской раскладке, если в нём почти нет английских гласных, а после
 * перевода по ЙЦУКЕН в нём нормальная для русского доля гласных. Идентификаторы (CamelCase, аббревиатуры),
 * пути, имена файлов, слова с цифрами и частые технические сокращения ({@code src}, {@code mvn}, {@code jvm})
 * не трогаются. Чтобы не исправлять случайные слова, переводится только сообщение, где таких слов хотя бы два
 * (или одно длинное), и однобуквенные слова — только рядом с переведёнными.
 */
final class KeyboardLayout {
    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>~&?";
    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбюёЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮЁ?,";
    private static final String RU_VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯ";
    private static final String EN_VOWELS = "aeiouAEIOU";
    /** Однобуквенные русские слова: «в», «с», «к», «у», «о», «и», «а», «я». */
    private static final Set<String> SINGLE = Set.of("в", "с", "к", "у", "о", "и", "а", "я");
    private static final Set<String> TECHNICAL = Set.of(
        "src", "jvm", "jdk", "jre", "cmd", "pom", "xml", "sql", "git", "mvn", "npm", "ssh", "dns", "tcp", "http", "https",
        "url", "uri", "css", "dto", "dao", "orm", "jpa", "jwt", "csv", "pdf", "rpc", "grpc", "kts", "cli", "gui", "tls",
        "ssl", "crlf", "lf", "bom", "md", "js", "ts", "py", "kt", "id", "ok", "db", "ci", "cd", "ui", "ux", "qa", "vs",
        // Английские слова почти без гласных, которые иначе выглядели бы как русские в чужой раскладке.
        "try", "sync", "async", "by", "my", "why", "hmm", "pls", "thx", "lgtm", "tsx", "jsx", "cfg", "env", "dev", "pwd",
        "ls", "cwd", "dir", "tmp", "lib", "bin", "log", "rm", "mkdir", "grep", "dry", "cry", "gym", "spy", "sky", "fry",
        "check", "html", "crypt", "rhythm", "scrypt", "nth", "len", "str", "fmt", "ctx", "req", "res", "msg", "err");
    private static final Pattern TOKEN = Pattern.compile("\\S+");
    /** Команды для распознавания в русской раскладке: «/рудз» → «/help». */
    private static final List<String> COMMANDS = List.of(
        "/help", "/clear", "/compact", "/memory", "/settings", "/trust", "/details", "/skills", "/skill", "/brief",
        "/review", "/plan", "/sessions", "/new", "/resume", "/exit", "/quit");

    private KeyboardLayout() {}

    /** Сообщение с исправленной раскладкой или то же сообщение, если исправлять нечего. */
    static String fix(String text) {
        if (text.startsWith("/")) return text;
        List<int[]> spans = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            spans.add(new int[]{matcher.start(), matcher.end()});
            tokens.add(matcher.group());
        }
        String[] converted = new String[tokens.size()];
        int words = 0;
        int letters = 0;
        for (int i = 0; i < tokens.size(); i++) {
            converted[i] = convertWord(tokens.get(i));
            if (converted[i] != null) {
                words++;
                letters += converted[i].length();
            }
        }
        if (words < 2 && letters < 7) return text;
        // Однобуквенные «d» (в), «c» (с) и т. п. — только между переведёнными словами или рядом с ними.
        for (int i = 0; i < tokens.size(); i++) {
            if (converted[i] != null || tokens.get(i).length() != 1) continue;
            String single = map(tokens.get(i));
            boolean neighbour = i > 0 && converted[i - 1] != null || i + 1 < tokens.size() && converted[i + 1] != null;
            if (neighbour && SINGLE.contains(single)) converted[i] = single;
        }
        // «Calc.add&» рядом с переведёнными словами: идентификатор остаётся, а «&» в конце — это «?».
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (converted[i] == null && token.length() > 1 && token.endsWith("&")) converted[i] = token.substring(0, token.length() - 1) + "?";
        }
        StringBuilder result = new StringBuilder();
        int last = 0;
        for (int i = 0; i < tokens.size(); i++) {
            result.append(text, last, spans.get(i)[0]);
            result.append(converted[i] != null ? converted[i] : tokens.get(i));
            last = spans.get(i)[1];
        }
        return result.append(text.substring(last)).toString();
    }

    /** Перевод слова или {@code null}, если это, вероятно, не слово в чужой раскладке. */
    static String convertWord(String token) {
        String trailing = "";
        String word = token;
        // Запятая и точка в конце — обычная пунктуация, а не клавиши «б» и «ю».
        while (word.length() > 1 && (word.endsWith(",") || word.endsWith(".") || word.endsWith("!") || word.endsWith(")"))) {
            trailing = word.charAt(word.length() - 1) + trailing;
            word = word.substring(0, word.length() - 1);
        }
        if (word.length() < 2) return null;
        int letterCount = 0;
        int enVowels = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (EN.indexOf(c) < 0) return null;
            if (Character.isLetter(c)) letterCount++;
            if (EN_VOWELS.indexOf(c) >= 0) enVowels++;
        }
        if (letterCount < 2) return null;
        String lower = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (TECHNICAL.contains(lower)) return null;
        if (word.equals(word.toUpperCase(Locale.ROOT)) && word.chars().filter(Character::isLetter).count() > 1) return null;
        if (hasInnerUppercase(word)) return null;
        String mapped = map(word);
        long ruLetters = mapped.chars().filter(c -> Character.isLetter(c)).count();
        long ruVowels = mapped.chars().filter(c -> RU_VOWELS.indexOf(c) >= 0).count();
        if (ruLetters < 2 || (double) enVowels / letterCount >= 0.2 || (double) ruVowels / ruLetters < 0.2) return null;
        // Русские слова не начинаются с «ы», «ь», «ъ»: «style» → «ыенду» — это английское слово.
        if ("ыьъЫЬЪ".indexOf(mapped.charAt(0)) >= 0) return null;
        if (!mapped.chars().allMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC || c == '?' || c == ',')) {
            return null;
        }
        return mapped + trailing;
    }

    private static boolean hasInnerUppercase(String word) {
        for (int i = 1; i < word.length(); i++) if (Character.isUpperCase(word.charAt(i))) return true;
        return false;
    }

    private static String map(String word) {
        StringBuilder result = new StringBuilder(word.length());
        for (char c : word.toCharArray()) {
            int index = EN.indexOf(c);
            result.append(index >= 0 ? RU.charAt(index) : c);
        }
        return result.toString();
    }

    /** Команда, набранная в русской раскладке («/рудз»), — известная команда («/help») или {@code null}. */
    static String command(String text) {
        if (!text.startsWith("/") || text.chars().noneMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC)) {
            return null;
        }
        int space = text.indexOf(' ');
        String head = space < 0 ? text : text.substring(0, space);
        StringBuilder english = new StringBuilder();
        for (char c : head.toCharArray()) {
            int index = RU.indexOf(c);
            english.append(index >= 0 && index < RU.length() - 2 ? EN.charAt(index) : c);
        }
        String candidate = english.toString();
        return COMMANDS.contains(candidate) ? candidate + (space < 0 ? "" : text.substring(space)) : null;
    }
}
