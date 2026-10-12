using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace LocalDrop.Core;

public sealed record DiscoveryRecord(string Id, string Name, string Type, int Port, string? Host = null, string Capabilities = "")
{
    public const string ServiceType = "_localdrop._tcp.local.";
    private static readonly Regex IdPattern = new("^[0-9a-f]{32}$", RegexOptions.Compiled);

    public static string NewId() => Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16));

    public static bool TryParse(IReadOnlyDictionary<string, string> txt, int port, out DiscoveryRecord? record)
    {
        record = null;
        if (!txt.TryGetValue("txtvers", out var txtVersion) || txtVersion != "1" ||
            !txt.TryGetValue("pv", out var protocolVersion) || protocolVersion != "1" ||
            !txt.TryGetValue("id", out var id) || !IdPattern.IsMatch(id) ||
            !txt.TryGetValue("name", out var name) || string.IsNullOrWhiteSpace(name) ||
            name.Any(char.IsControl) ||
            Encoding.UTF8.GetByteCount(name) > 64 ||
            !txt.TryGetValue("type", out var type) || type is not ("windows" or "android") ||
            !txt.TryGetValue("caps", out var caps) || caps is not ("" or "file1") ||
            port is < 1 or > 65535)
            return false;
        record = new(id, name, type, port, Capabilities: caps);
        return true;
    }
}
