using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace LocalDrop.Core;

public static class LdProtocol
{
    public const int MaxFrameBytes = 16_384;
    public const long MaxFileBytes = 2L * 1024 * 1024 * 1024 * 1024;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    public static async Task WriteAsync(Stream stream, object message, CancellationToken cancellationToken)
    {
        var body = JsonSerializer.SerializeToUtf8Bytes(message, JsonOptions);
        if (body.Length is < 1 or > MaxFrameBytes) throw new InvalidDataException("Control frame is too large.");
        var length = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(length, body.Length);
        await stream.WriteAsync(length, cancellationToken);
        await stream.WriteAsync(body, cancellationToken);
        await stream.FlushAsync(cancellationToken);
    }

    public static async Task<JsonDocument> ReadAsync(Stream stream, CancellationToken cancellationToken)
    {
        var lengthBytes = new byte[4];
        await stream.ReadExactlyAsync(lengthBytes, cancellationToken);
        var length = BinaryPrimitives.ReadInt32BigEndian(lengthBytes);
        if (length is < 1 or > MaxFrameBytes) throw new InvalidDataException("Invalid control frame length.");
        var body = new byte[length];
        await stream.ReadExactlyAsync(body, cancellationToken);
        try
        {
            var json = JsonDocument.Parse(StrictUtf8.GetString(body));
            if (json.RootElement.ValueKind != JsonValueKind.Object || HasDuplicateProperties(json.RootElement))
            {
                json.Dispose();
                throw new InvalidDataException("Invalid control frame object.");
            }
            return json;
        }
        catch (Exception e) when (e is JsonException or DecoderFallbackException)
        {
            throw new InvalidDataException("Invalid control frame JSON.", e);
        }
    }

    private static bool HasDuplicateProperties(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
                if (!names.Add(property.Name) || HasDuplicateProperties(property.Value)) return true;
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var item in element.EnumerateArray())
                if (HasDuplicateProperties(item)) return true;
        return false;
    }

    public static string RequiredString(JsonElement frame, string key, int maxBytes)
    {
        if (!frame.TryGetProperty(key, out var value) || value.ValueKind != JsonValueKind.String)
            throw new InvalidDataException($"Missing {key}.");
        var text = value.GetString()!;
        if (Encoding.UTF8.GetByteCount(text) > maxBytes) throw new InvalidDataException($"Invalid {key}.");
        return text;
    }

    public static void RequireKind(JsonElement frame, string kind)
    {
        if (RequiredString(frame, "kind", 32) != kind) throw new InvalidDataException("Unexpected frame kind.");
    }

    public static bool RequiredBool(JsonElement frame, string key)
    {
        if (!frame.TryGetProperty(key, out var value) || value.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
            throw new InvalidDataException($"Missing {key}.");
        return value.GetBoolean();
    }

    public static long RequiredLength(JsonElement frame)
    {
        if (!frame.TryGetProperty("length", out var value) || value.ValueKind != JsonValueKind.Number ||
            !value.TryGetInt64(out var length) ||
            length is < 0 or > MaxFileBytes) throw new InvalidDataException("Invalid file length.");
        return length;
    }

    public static string ValidateFileName(string name)
    {
        if (string.IsNullOrWhiteSpace(name) || Encoding.UTF8.GetByteCount(name) > 255 ||
            name is "." or ".." || name.EndsWith(' ') || name.EndsWith('.') ||
            name.Any(c => char.IsControl(c) || c is '/' or '\\' or ':' or '*' or '?' or '"' or '<' or '>' or '|'))
            throw new InvalidDataException("Invalid file name.");
        var stem = name.Split('.')[0];
        if (new[] { "CON", "PRN", "AUX", "NUL" }.Contains(stem, StringComparer.OrdinalIgnoreCase) ||
            (stem.Length == 4 && (stem.StartsWith("COM", StringComparison.OrdinalIgnoreCase) ||
                                  stem.StartsWith("LPT", StringComparison.OrdinalIgnoreCase)) && stem[3] is >= '1' and <= '9'))
            throw new InvalidDataException("Reserved file name.");
        return name;
    }

    public static string SecurityCode(byte[] clientCertHash, byte[] serverCertHash, byte[] clientNonce, byte[] serverNonce)
    {
        if (clientCertHash.Length != 32 || serverCertHash.Length != 32 || clientNonce.Length != 32 || serverNonce.Length != 32)
            throw new ArgumentException("Invalid security code input.");
        var label = Encoding.UTF8.GetBytes("LD/1 SAS\0");
        var input = new byte[label.Length + 128];
        label.CopyTo(input, 0);
        clientCertHash.CopyTo(input, label.Length);
        serverCertHash.CopyTo(input, label.Length + 32);
        clientNonce.CopyTo(input, label.Length + 64);
        serverNonce.CopyTo(input, label.Length + 96);
        var value = BinaryPrimitives.ReadUInt64BigEndian(SHA256.HashData(input)) % 1_000_000_000_000UL;
        var digits = value.ToString("D12");
        return $"{digits[..4]} {digits[4..8]} {digits[8..]}";
    }

    public static async Task<string> CopyExactlyAsync(Stream source, Stream destination, long length,
        Action<long>? progress, CancellationToken cancellationToken)
    {
        if (length is < 0 or > MaxFileBytes) throw new ArgumentOutOfRangeException(nameof(length));
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var buffer = new byte[64 * 1024];
        long copied = 0;
        while (copied < length)
        {
            var read = await source.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, length - copied)),
                cancellationToken);
            if (read == 0) throw new EndOfStreamException("File stream ended early.");
            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            digest.AppendData(buffer.AsSpan(0, read));
            copied += read;
            progress?.Invoke(copied);
        }
        return Convert.ToHexStringLower(digest.GetHashAndReset());
    }
}
