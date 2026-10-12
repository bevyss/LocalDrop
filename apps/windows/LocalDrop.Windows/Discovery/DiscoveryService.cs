using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using LocalDrop.Core;
using Makaretu.Dns;

namespace LocalDrop_Windows.Discovery;

public sealed class DiscoveryService : IDisposable
{
    private readonly string _ownId;
    private readonly MulticastService _mdns = new();
    private readonly ServiceDiscovery _dnsSd;
    private readonly ConcurrentDictionary<string, (int Port, string Host, DateTimeOffset Seen)> _servers = new();
    private readonly ConcurrentDictionary<string, (Dictionary<string, string> Txt, DateTimeOffset Seen)> _texts = new();
    private readonly ConcurrentDictionary<string, DateTimeOffset> _lastSeen = new();
    private readonly TcpListener _listener = new(IPAddress.Any, 0);
    private readonly CancellationTokenSource _stop = new();
    private readonly System.Threading.Timer _maintenance;
    private ServiceProfile? _profile;
    private readonly Func<TcpClient, Task> _incoming;

    public event Action<DiscoveryRecord>? PeerSeen;
    public event Action<string>? PeerLost;
    public event Action<string>? Error;

    public DiscoveryService(string ownId, Func<TcpClient, Task> incoming)
    {
        _ownId = ownId;
        _incoming = incoming;
        _dnsSd = new ServiceDiscovery(_mdns);
        _dnsSd.ServiceInstanceDiscovered += (_, e) =>
        {
            if (!IsLocalDrop(e.ServiceInstanceName.ToString())) return;
            _mdns.SendQuery(e.ServiceInstanceName, type: DnsType.SRV);
            _mdns.SendQuery(e.ServiceInstanceName, type: DnsType.TXT);
        };
        _dnsSd.ServiceInstanceShutdown += (_, e) => RemoveByInstance(e.ServiceInstanceName.ToString());
        _mdns.AnswerReceived += (_, e) => ReadAnswers(e.Message, e.RemoteEndPoint.Address.ToString());
        _mdns.NetworkInterfaceDiscovered += (_, _) => Query();
        _maintenance = new System.Threading.Timer(_ => Maintain(), null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start(string deviceName)
    {
        _listener.Start();
        _ = AcceptConnectionsAsync(_stop.Token);
        var port = (ushort)((IPEndPoint)_listener.LocalEndpoint).Port;
        // The ID suffix makes instance collisions improbable without exposing a hardware ID.
        var shortName = deviceName;
        while (Encoding.UTF8.GetByteCount(shortName) > 50) shortName = shortName[..^1];
        _profile = new ServiceProfile($"{shortName}-{_ownId[..8]}", "_localdrop._tcp", port);
        _profile.AddProperty("pv", "1");
        _profile.AddProperty("id", _ownId);
        _profile.AddProperty("name", deviceName);
        _profile.AddProperty("type", "windows");
        _profile.AddProperty("caps", "file1");
        _mdns.Start();
        _dnsSd.Advertise(_profile);
        // Advertising responds to DNS-SD queries; a separate unsolicited announcement is optional.
        Query();
        _maintenance.Change(TimeSpan.FromSeconds(15), TimeSpan.FromSeconds(15));
    }

    private async Task AcceptConnectionsAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var client = await _listener.AcceptTcpClientAsync(cancellationToken);
                _ = HandleConnectionAsync(client);
            }
        }
        catch (OperationCanceledException) { }
        catch (ObjectDisposedException) { }
        catch (Exception ex) { Error?.Invoke($"Listener failed: {ex.Message}"); }
    }

    private async Task HandleConnectionAsync(TcpClient client)
    {
        using (client)
        {
            try { await _incoming(client); }
            catch (Exception ex) { AppLog.Write("error", "connection_failed", ex.ToString()); }
        }
    }

    private static bool IsLocalDrop(string instance) =>
        instance.TrimEnd('.').EndsWith("._localdrop._tcp.local", StringComparison.OrdinalIgnoreCase);

    private void ReadAnswers(Message message, string host)
    {
        foreach (var record in message.Answers.Concat(message.AdditionalRecords))
        {
            var key = record.Name.ToString().TrimEnd('.').ToLowerInvariant();
            if (!IsLocalDrop(key)) continue;
            if (record is SRVRecord srv)
                _servers[key] = (srv.Port, host, DateTimeOffset.UtcNow);
            else if (record is TXTRecord txt)
            {
                var properties = new Dictionary<string, string>(StringComparer.Ordinal);
                foreach (var item in txt.Strings)
                {
                    var split = item.IndexOf('=');
                    if (split > 0 && !properties.ContainsKey(item[..split]))
                        properties[item[..split]] = item[(split + 1)..];
                }
                _texts[key] = (properties, DateTimeOffset.UtcNow);
            }
            TryPublish(key);
        }
    }

    private void TryPublish(string instance)
    {
        if (!_servers.TryGetValue(instance, out var server) ||
            !_texts.TryGetValue(instance, out var text) ||
            !DiscoveryRecord.TryParse(text.Txt, server.Port, out var peer) || peer is null || peer.Id == _ownId)
            return;
        _lastSeen[peer.Id] = DateTimeOffset.UtcNow;
        PeerSeen?.Invoke(peer with { Host = server.Host });
    }

    private void RemoveByInstance(string instance)
    {
        var key = instance.TrimEnd('.').ToLowerInvariant();
        _servers.TryRemove(key, out _);
        if (_texts.TryRemove(key, out var entry) && entry.Txt.TryGetValue("id", out var id))
        {
            _lastSeen.TryRemove(id, out _);
            PeerLost?.Invoke(id);
        }
    }

    private void Query() => _dnsSd.QueryServiceInstances("_localdrop._tcp.local");

    private void Maintain()
    {
        try
        {
            Query();
            var cutoff = DateTimeOffset.UtcNow.AddSeconds(-90);
            foreach (var peer in _lastSeen)
                if (peer.Value < cutoff && _lastSeen.TryRemove(peer.Key, out _)) PeerLost?.Invoke(peer.Key);
        }
        catch (Exception ex) { Error?.Invoke($"Discovery query failed: {ex.Message}"); }
    }

    public void Dispose()
    {
        _maintenance.Dispose();
        _stop.Cancel();
        _listener.Stop();
        if (_profile is not null) _dnsSd.Unadvertise(_profile);
        _dnsSd.Dispose();
        _mdns.Stop();
        _mdns.Dispose();
        _stop.Dispose();
    }
}
