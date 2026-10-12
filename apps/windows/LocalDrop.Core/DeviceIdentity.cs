namespace LocalDrop.Core;

public static class DeviceIdentity
{
    public static string LoadOrCreate(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        if (File.Exists(path))
        {
            var existing = File.ReadAllText(path).Trim();
            if (existing.Length == 32 && existing.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f'))
                return existing;
            throw new InvalidDataException("Stored device identity is invalid.");
        }
        var id = DiscoveryRecord.NewId();
        using var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        using var writer = new StreamWriter(stream);
        writer.Write(id);
        return id;
    }
}
