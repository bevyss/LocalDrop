using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace LocalDrop_Windows.Transfer;

internal sealed class LocalCredentials
{
    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LocalDrop");
    private readonly object _sync = new();
    private Dictionary<string, string> _trusted;

    public X509Certificate2 Certificate { get; }

    public LocalCredentials(string deviceId)
    {
        Directory.CreateDirectory(_directory);
        var certificatePath = Path.Combine(_directory, "tls-certificate.dpapi");
        if (File.Exists(certificatePath))
        {
            var protectedBytes = File.ReadAllBytes(certificatePath);
            var pfx = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
            try { Certificate = LoadForWindowsTls(pfx); }
            finally { CryptographicOperations.ZeroMemory(pfx); }
        }
        else
        {
            using var rsa = RSA.Create(2048);
            var request = new CertificateRequest($"CN=LocalDrop-{deviceId}", rsa, HashAlgorithmName.SHA256,
                RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
            var eku = new OidCollection
            {
                new("1.3.6.1.5.5.7.3.1"), // TLS server
                new("1.3.6.1.5.5.7.3.2")  // TLS client
            };
            request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(eku, false));
            using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
            var pfx = generated.Export(X509ContentType.Pkcs12);
            try
            {
                var protectedBytes = ProtectedData.Protect(pfx, null, DataProtectionScope.CurrentUser);
                using var file = new FileStream(certificatePath, FileMode.CreateNew, FileAccess.Write, FileShare.None);
                file.Write(protectedBytes);
                Certificate = LoadForWindowsTls(pfx);
            }
            finally { CryptographicOperations.ZeroMemory(pfx); }
        }
        var trustPath = Path.Combine(_directory, "trusted-peers.json");
        _trusted = File.Exists(trustPath)
            ? JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(trustPath)) ?? new()
            : new();
    }

    private static X509Certificate2 LoadForWindowsTls(byte[] pfx) =>
        X509CertificateLoader.LoadPkcs12(pfx, null,
            X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.PersistKeySet);

    public bool IsTrusted(string id, string certificateHash)
    {
        lock (_sync) return _trusted.TryGetValue(id, out var expected) && expected == certificateHash;
    }

    public void Trust(string id, string certificateHash)
    {
        lock (_sync)
        {
            _trusted[id] = certificateHash;
            var path = Path.Combine(_directory, "trusted-peers.json");
            var temp = path + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(_trusted));
            File.Move(temp, path, overwrite: true);
        }
    }
}
