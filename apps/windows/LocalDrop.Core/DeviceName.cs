using System.Text;

namespace LocalDrop.Core;

public static class DeviceName
{
    public static string Normalize(string value)
    {
        var name = value.Trim();
        if (name.Length == 0 || Encoding.UTF8.GetByteCount(name) > 64 || name.Any(char.IsControl))
            throw new ArgumentException("Device name must be 1–64 UTF-8 bytes without control characters.", nameof(value));
        return name;
    }

    public static string LoadOrDefault(string path, string fallback)
    {
        if (!File.Exists(path)) return Normalize(fallback);
        try { return Normalize(File.ReadAllText(path, Encoding.UTF8)); }
        catch (ArgumentException) { return Normalize(fallback); }
    }

    public static void Save(string path, string value)
    {
        var name = Normalize(value);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + ".tmp";
        File.WriteAllText(temporary, name, Encoding.UTF8);
        File.Move(temporary, path, true);
    }
}
