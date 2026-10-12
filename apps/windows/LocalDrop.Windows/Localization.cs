using System.Globalization;

namespace LocalDrop_Windows;

internal static class Localization
{
    private static readonly string[] Languages = ["en", "tr", "de", "es", "zh", "ja"];
    private static readonly Dictionary<string, string[]> Values = Load();
    private static readonly string LanguageFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LocalDrop", "language");

    public static string Language { get; private set; } = LoadLanguage();

    public static void SetLanguage(string language)
    {
        if (!Languages.Contains(language)) return;
        Language = language;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(LanguageFile)!);
            File.WriteAllText(LanguageFile, language);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    public static string Text(string key, params object[] args)
    {
        if (!Values.TryGetValue(key, out var translations)) return key;
        var index = Array.IndexOf(Languages, Language);
        var value = translations[index < 0 || index >= translations.Length ? 0 : index];
        if (string.IsNullOrWhiteSpace(value)) value = translations[0];
        return args.Length == 0 ? value : string.Format(CultureInfo.CurrentCulture, value, args);
    }

    private static string LoadLanguage()
    {
        try
        {
            var stored = File.ReadAllText(LanguageFile).Trim();
            if (Languages.Contains(stored)) return stored;
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        var system = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName;
        return Languages.Contains(system) ? system : "en";
    }

    private static Dictionary<string, string[]> Load()
    {
        using var stream = typeof(Localization).Assembly.GetManifestResourceStream("LocalDrop.Localization.strings.tsv")
            ?? throw new InvalidOperationException("Localization resource is missing.");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd().Split('\n').Skip(1)
            .Where(line => !string.IsNullOrWhiteSpace(line))
            .Select(line => line.TrimEnd('\r').Split('\t'))
            .ToDictionary(columns => columns[0], columns => columns.Skip(1).ToArray());
    }
}
