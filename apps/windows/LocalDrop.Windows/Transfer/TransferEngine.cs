using System.Net;
using System.Diagnostics;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using LocalDrop.Core;

namespace LocalDrop_Windows.Transfer;

internal sealed class TransferEngine : IDisposable
{
    private readonly string _id;
    private readonly string _name;
    private readonly LocalCredentials _credentials;
    private readonly Func<string, string, Task<bool>> _verify;
    private readonly Func<string, string, long, Task<bool>> _accept;
    private readonly Action<string> _status;
    private readonly Action _onReceived;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();
    private CancellationTokenSource? _active;
    private int _userCancelled;
    public bool IsBusy => _gate.CurrentCount == 0;

    public TransferEngine(string id, string name, Func<string, string, Task<bool>> verify,
        Func<string, string, long, Task<bool>> accept, Action<string> status, Action onReceived)
    {
        _id = id;
        _name = name;
        _credentials = new LocalCredentials(id);
        _verify = verify;
        _accept = accept;
        _status = status;
        _onReceived = onReceived;
    }

    public void Cancel()
    {
        var active = _active;
        if (active is null) return;
        Volatile.Write(ref _userCancelled, 1);
        try { active.Cancel(); }
        catch (ObjectDisposedException) { }
    }

    public async Task ReceiveAsync(TcpClient client)
    {
        if (!await _gate.WaitAsync(0)) return;
        using var session = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _active = session;
        Volatile.Write(ref _userCancelled, 0);
        var secureConnection = false;
        try
        {
            client.NoDelay = true;
            using var abortConnection = session.Token.Register(static state => ((TcpClient)state!).Dispose(), client);
            using var tls = NewStream(client);
            session.CancelAfter(TimeSpan.FromMinutes(5));
            await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificate = _credentials.Certificate,
                ClientCertificateRequired = true,
                EnabledSslProtocols = SslProtocols.Tls12,
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck
            }, session.Token);
            secureConnection = true;
            var remoteHash = PeerHash(tls);
            var peerHello = await ReadHelloAsync(tls, session.Token);
            var known = _credentials.IsTrusted(peerHello.Id, remoteHash);
            var serverNonce = RandomNumberGenerator.GetBytes(32);
            await SendHelloAsync(tls, serverNonce, known, session.Token);
            await VerifyAsync(tls, peerHello.Name, peerHello.Id, remoteHash, peerHello.Known, known,
                HexToBytes(peerHello.Nonce, 32), serverNonce, false, session.Token);
            session.CancelAfter(TimeSpan.FromMinutes(5));
            await ReceiveFileAsync(tls, peerHello.Name, session);
        }
        catch (Exception) when (session.IsCancellationRequested)
        {
            ReportCancellation();
        }
        catch (Exception ex)
        {
            _status(!secureConnection && ex is IOException ? Localization.Text("secure_error") : UserError(ex));
            AppLog.Write("error", "incoming_transfer_failed", ex.ToString());
        }
        finally { _active = null; _gate.Release(); }
    }

    public async Task SendAsync(DiscoveryRecord peer, string filePath)
    {
        if (peer.Capabilities != "file1" || string.IsNullOrWhiteSpace(peer.Host))
            throw new InvalidOperationException("Device is unavailable for file transfer.");
        if (!await _gate.WaitAsync(0)) throw new InvalidOperationException("A transfer is already active.");
        using var session = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _active = session;
        Volatile.Write(ref _userCancelled, 0);
        var secureConnection = false;
        try
        {
            var file = new FileInfo(filePath);
            var fileName = LdProtocol.ValidateFileName(file.Name);
            if (file.Length > LdProtocol.MaxFileBytes) throw new InvalidDataException("File is too large.");
            await using var source = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read,
                64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            var address = IPAddress.Parse(peer.Host);
            using var tcp = new TcpClient(address.AddressFamily);
            using var abortConnection = session.Token.Register(static state => ((TcpClient)state!).Dispose(), tcp);
            session.CancelAfter(TimeSpan.FromSeconds(30));
            await tcp.ConnectAsync(address, peer.Port, session.Token);
            tcp.NoDelay = true;
            session.CancelAfter(TimeSpan.FromMinutes(5));
            using var tls = NewStream(tcp);
            await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = "localdrop",
                ClientCertificates = new X509CertificateCollection { _credentials.Certificate },
                EnabledSslProtocols = SslProtocols.Tls12,
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck
            }, session.Token);
            secureConnection = true;
            var remoteHash = PeerHash(tls);
            var known = _credentials.IsTrusted(peer.Id, remoteHash);
            var clientNonce = RandomNumberGenerator.GetBytes(32);
            await SendHelloAsync(tls, clientNonce, known, session.Token);
            var server = await ReadHelloAsync(tls, session.Token);
            if (server.Id != peer.Id) throw new InvalidDataException("Discovered device identity changed.");
            await VerifyAsync(tls, server.Name, server.Id, remoteHash, known, server.Known,
                clientNonce, HexToBytes(server.Nonce, 32), true, session.Token);
            session.CancelAfter(TimeSpan.FromMinutes(5));
            var transferId = DiscoveryRecord.NewId();
            await LdProtocol.WriteAsync(tls, new { kind = "request", id = transferId, name = fileName,
                length = file.Length }, session.Token);
            using var reply = await LdProtocol.ReadAsync(tls, session.Token);
            LdProtocol.RequireKind(reply.RootElement, "response");
            if (!LdProtocol.RequiredBool(reply.RootElement, "accept"))
            {
                var reason = LdProtocol.RequiredString(reply.RootElement, "reason", 64);
                _status(Localization.Text(reason == "no_space" ? "receiver_space" : "receiver_declined"));
                AppLog.Write("info", "transfer_declined");
                return;
            }
            session.CancelAfter(TimeSpan.FromSeconds(30));
            _status(Localization.Text("sending", fileName));
            AppLog.Write("info", "transfer_started", transferId);
            long nextUpdate = 0;
            var clock = Stopwatch.StartNew();
            var hash = await LdProtocol.CopyExactlyAsync(source, tls, file.Length, sent =>
            {
                session.CancelAfter(TimeSpan.FromSeconds(30));
                if (sent >= nextUpdate)
                {
                    _status(ProgressText(Localization.Text("sending", fileName), sent, file.Length, clock.Elapsed));
                    nextUpdate = sent + 1_048_576;
                }
            }, session.Token);
            await LdProtocol.WriteAsync(tls, new { kind = "complete",
                sha256 = hash }, session.Token);
            using var result = await LdProtocol.ReadAsync(tls, session.Token);
            LdProtocol.RequireKind(result.RootElement, "result");
            if (!LdProtocol.RequiredBool(result.RootElement, "ok"))
                throw new IOException("Receiver could not save the file.");
            _status(Localization.Text("sent", fileName));
            AppLog.Write("info", "transfer_completed", transferId);
        }
        catch (Exception) when (session.IsCancellationRequested)
        {
            ReportCancellation();
        }
        catch (Exception ex)
        {
            _status(!secureConnection && ex is IOException ? Localization.Text("secure_error") : UserError(ex));
            AppLog.Write("error", "outgoing_transfer_failed", ex.ToString());
        }
        finally { _active = null; _gate.Release(); }
    }

    private async Task ReceiveFileAsync(SslStream tls, string sender, CancellationTokenSource session)
    {
        var cancellationToken = session.Token;
        using var request = await LdProtocol.ReadAsync(tls, cancellationToken);
        var frame = request.RootElement;
        LdProtocol.RequireKind(frame, "request");
        var id = LdProtocol.RequiredString(frame, "id", 32);
        _ = HexToBytes(id, 16);
        var fileName = LdProtocol.ValidateFileName(LdProtocol.RequiredString(frame, "name", 255));
        var length = LdProtocol.RequiredLength(frame);
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads", "LocalDrop");
        Directory.CreateDirectory(directory);
        if (new DriveInfo(Path.GetPathRoot(directory)!).AvailableFreeSpace < length)
        {
            await LdProtocol.WriteAsync(tls, new { kind = "response", accept = false, reason = "no_space" }, cancellationToken);
            _status(Localization.Text("no_space"));
            return;
        }
        AppLog.Write("info", "transfer_requested", id);
        var accepted = await _accept(sender, fileName, length).WaitAsync(cancellationToken);
        await LdProtocol.WriteAsync(tls, new { kind = "response", accept = accepted,
            reason = accepted ? "" : "declined" }, cancellationToken);
        if (!accepted) { AppLog.Write("info", "transfer_declined", id); return; }
        AppLog.Write("info", "transfer_accepted", id);
        session.CancelAfter(TimeSpan.FromSeconds(30));
        var tempPath = Path.Combine(directory, $".{id}.partial");
        try
        {
            await using var destination = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            long nextUpdate = 0;
            var clock = Stopwatch.StartNew();
            var actualHash = await LdProtocol.CopyExactlyAsync(tls, destination, length, received =>
            {
                session.CancelAfter(TimeSpan.FromSeconds(30));
                if (received >= nextUpdate)
                {
                    _status(ProgressText(Localization.Text("receiving", fileName), received, length, clock.Elapsed));
                    nextUpdate = received + 1_048_576;
                }
            }, cancellationToken);
            await destination.FlushAsync(cancellationToken);
            using var complete = await LdProtocol.ReadAsync(tls, cancellationToken);
            LdProtocol.RequireKind(complete.RootElement, "complete");
            var expected = HexToBytes(LdProtocol.RequiredString(complete.RootElement, "sha256", 64), 32);
            if (!CryptographicOperations.FixedTimeEquals(expected, Convert.FromHexString(actualHash)))
                throw new InvalidDataException("File checksum mismatch.");
            await destination.DisposeAsync();
            var finalPath = UniqueDestination(directory, fileName);
            File.Move(tempPath, finalPath);
            await LdProtocol.WriteAsync(tls, new { kind = "result", ok = true }, cancellationToken);
            _status(Localization.Text("received_folder", fileName));
            _onReceived();
            AppLog.Write("info", "transfer_completed", id);
        }
        finally { if (File.Exists(tempPath)) File.Delete(tempPath); }
    }

    private static string UniqueDestination(string directory, string fileName)
    {
        for (var i = 0; i < 1000; i++)
        {
            var stem = Path.GetFileNameWithoutExtension(fileName);
            var extension = Path.GetExtension(fileName);
            var candidate = Path.Combine(directory, i == 0 ? fileName : $"{stem} ({i}){extension}");
            if (!File.Exists(candidate)) return candidate;
        }
        throw new IOException("Too many files with the same name.");
    }

    private async Task VerifyAsync(SslStream tls, string peerName, string peerId, string peerHash,
        bool clientKnown, bool serverKnown, byte[] clientNonce, byte[] serverNonce,
        bool isClient, CancellationToken cancellationToken)
    {
        var ownHash = SHA256.HashData(_credentials.Certificate.RawData);
        var otherHash = Convert.FromHexString(peerHash);
        var code = LdProtocol.SecurityCode(isClient ? ownHash : otherHash,
            isClient ? otherHash : ownHash, clientNonce, serverNonce);
        var approved = clientKnown && serverKnown || await _verify(peerName, code).WaitAsync(cancellationToken);
        if (isClient)
        {
            await LdProtocol.WriteAsync(tls, new { kind = "verify", accept = approved }, cancellationToken);
            using var reply = await LdProtocol.ReadAsync(tls, cancellationToken);
            LdProtocol.RequireKind(reply.RootElement, "verify");
            if (!approved || !LdProtocol.RequiredBool(reply.RootElement, "accept"))
                throw new AuthenticationException("Peer verification declined.");
        }
        else
        {
            using var reply = await LdProtocol.ReadAsync(tls, cancellationToken);
            LdProtocol.RequireKind(reply.RootElement, "verify");
            await LdProtocol.WriteAsync(tls, new { kind = "verify", accept = approved }, cancellationToken);
            if (!approved || !LdProtocol.RequiredBool(reply.RootElement, "accept"))
                throw new AuthenticationException("Peer verification declined.");
        }
        _credentials.Trust(peerId, peerHash);
        AppLog.Write("info", "peer_verified", peerId);
    }

    private async Task SendHelloAsync(SslStream tls, byte[] nonce, bool known, CancellationToken cancellationToken) =>
        await LdProtocol.WriteAsync(tls, new { kind = "hello", pv = 1, id = _id, name = _name,
            type = "windows", nonce = Convert.ToHexStringLower(nonce), known }, cancellationToken);

    private static async Task<(string Id, string Name, string Nonce, bool Known)> ReadHelloAsync(
        SslStream tls, CancellationToken cancellationToken)
    {
        using var message = await LdProtocol.ReadAsync(tls, cancellationToken);
        var frame = message.RootElement;
        LdProtocol.RequireKind(frame, "hello");
        if (!frame.TryGetProperty("pv", out var version) || version.ValueKind != JsonValueKind.Number ||
            version.GetInt32() != 1) throw new InvalidDataException("Unsupported LocalDrop protocol version.");
        var id = LdProtocol.RequiredString(frame, "id", 32);
        _ = HexToBytes(id, 16);
        var name = LdProtocol.RequiredString(frame, "name", 64);
        if (string.IsNullOrWhiteSpace(name) || name.Any(char.IsControl)) throw new InvalidDataException("Invalid device name.");
        var type = LdProtocol.RequiredString(frame, "type", 16);
        if (type is not ("windows" or "android")) throw new InvalidDataException("Invalid device type.");
        var nonce = LdProtocol.RequiredString(frame, "nonce", 64);
        _ = HexToBytes(nonce, 32);
        return (id, name, nonce, LdProtocol.RequiredBool(frame, "known"));
    }

    private static byte[] HexToBytes(string value, int size)
    {
        if (value.Length != size * 2 || !value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f'))
            throw new InvalidDataException("Invalid hexadecimal field.");
        return Convert.FromHexString(value);
    }

    private static SslStream NewStream(TcpClient client) => new(client.GetStream(), false,
        (_, certificate, _, _) => certificate is not null);

    private static string PeerHash(SslStream tls)
    {
        if (tls.RemoteCertificate is null) throw new AuthenticationException("Peer did not provide a certificate.");
        return Convert.ToHexStringLower(SHA256.HashData(tls.RemoteCertificate.GetRawCertData()));
    }

    private static string UserError(Exception error) => error switch
    {
        AuthenticationException => Localization.Text("security_failed"),
        InvalidDataException => Localization.Text("invalid_message"),
        FileNotFoundException or UnauthorizedAccessException => Localization.Text("file_io"),
        SocketException or IOException { InnerException: SocketException } => Localization.Text("network_lost"),
        EndOfStreamException or IOException => Localization.Text("file_io"),
        _ => Localization.Text("transfer_failed")
    };

    private void ReportCancellation()
    {
        if (Volatile.Read(ref _userCancelled) != 0)
        {
            _status(Localization.Text("transfer_cancelled"));
            AppLog.Write("info", "transfer_cancelled");
        }
        else
        {
            _status(Localization.Text("network_timeout"));
            AppLog.Write("error", "transfer_timed_out");
        }
    }

    private static string ProgressText(string action, long bytes, long total, TimeSpan elapsed)
    {
        var seconds = Math.Max(elapsed.TotalSeconds, 0.001);
        var speed = bytes / seconds;
        var remaining = speed > 0 ? TimeSpan.FromSeconds((total - bytes) / speed) : TimeSpan.Zero;
        var percent = total == 0 ? 100 : 100.0 * bytes / total;
        return Localization.Text("progress", action, bytes.ToString("N0"), total.ToString("N0"),
            percent.ToString("F1"), (speed / 1048576).ToString("F1"), remaining.TotalSeconds.ToString("F0"));
    }

    public void Dispose()
    {
        _lifetime.Cancel();
        _active?.Cancel();
        _credentials.Certificate.Dispose();
        _lifetime.Dispose();
        _gate.Dispose();
    }
}
