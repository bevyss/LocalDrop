using System.Text.Json;

namespace LocalDrop_Windows;

internal static class AppLog
{
    private static readonly object Sync = new();
    private static readonly string PathName = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LocalDrop", "localdrop.log");

    public static void Write(string level, string eventName, string? detail = null)
    {
        try
        {
            lock (Sync)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(PathName)!);
                if (File.Exists(PathName) && new FileInfo(PathName).Length > 1_000_000)
                    File.Move(PathName, PathName + ".old", overwrite: true);
                File.AppendAllText(PathName, JsonSerializer.Serialize(new
                {
                    time = DateTimeOffset.UtcNow,
                    level,
                    @event = eventName,
                    detail
                }) + Environment.NewLine);
            }
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
