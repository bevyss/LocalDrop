using System.Buffers.Binary;
using System.Text.Json;
using LocalDrop.Core;

namespace LocalDrop.Core.Tests;

public class TransferTests
{
    [Fact]
    public void SharedSecurityCodeAndFileNames()
    {
        using var vector = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "transfer.json")));
        var root = vector.RootElement;
        var code = LdProtocol.SecurityCode(
            Convert.FromHexString(root.GetProperty("clientCertificateSha256").GetString()!),
            Convert.FromHexString(root.GetProperty("serverCertificateSha256").GetString()!),
            Convert.FromHexString(root.GetProperty("clientNonce").GetString()!),
            Convert.FromHexString(root.GetProperty("serverNonce").GetString()!));
        Assert.Equal(root.GetProperty("securityCode").GetString(), code);
        Assert.Equal("photo 例.jpg", LdProtocol.ValidateFileName(root.GetProperty("validRequest").GetProperty("name").GetString()!));
        foreach (var bad in root.GetProperty("invalidFileNames").EnumerateArray())
            Assert.Throws<InvalidDataException>(() => LdProtocol.ValidateFileName(bad.GetString()!));
    }

    [Fact]
    public async Task FramesRoundTripAndRejectBadLengths()
    {
        using var stream = new MemoryStream();
        await LdProtocol.WriteAsync(stream, new { kind = "verify", accept = true }, CancellationToken.None);
        stream.Position = 0;
        using var frame = await LdProtocol.ReadAsync(stream, CancellationToken.None);
        LdProtocol.RequireKind(frame.RootElement, "verify");
        Assert.True(LdProtocol.RequiredBool(frame.RootElement, "accept"));
        stream.Position = 0;
        BinaryPrimitives.WriteInt32BigEndian(stream.GetBuffer().AsSpan(0, 4), LdProtocol.MaxFrameBytes + 1);
        await Assert.ThrowsAsync<InvalidDataException>(() => LdProtocol.ReadAsync(stream, CancellationToken.None));
    }

    [Fact]
    public async Task RejectsDuplicateJsonKeys()
    {
        var body = System.Text.Encoding.UTF8.GetBytes("{\"kind\":\"verify\",\"kind\":\"request\"}");
        using var stream = new MemoryStream();
        var header = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(header, body.Length);
        stream.Write(header);
        stream.Write(body);
        stream.Position = 0;
        await Assert.ThrowsAsync<InvalidDataException>(() => LdProtocol.ReadAsync(stream, CancellationToken.None));
    }

    [Fact]
    public async Task StreamsLargeFileWithBoundedReadsAndSupportsCancellation()
    {
        const long length = 128L * 1024 * 1024;
        await using var source = new RepeatingStream(length);
        var hash = await LdProtocol.CopyExactlyAsync(source, Stream.Null, length, null, CancellationToken.None);
        Assert.Equal(length, source.BytesRead);
        Assert.InRange(source.LargestRead, 1, 64 * 1024);
        Assert.Equal(64, hash.Length);
        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            LdProtocol.CopyExactlyAsync(new RepeatingStream(1024), Stream.Null, 1024, null, cancelled.Token));
    }

    private sealed class RepeatingStream(long size) : Stream
    {
        public long BytesRead { get; private set; }
        public int LargestRead { get; private set; }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => size;
        public override long Position { get => BytesRead; set => throw new NotSupportedException(); }
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var count = (int)Math.Min(buffer.Length, size - BytesRead);
            buffer.Span[..count].Clear();
            BytesRead += count;
            LargestRead = Math.Max(LargestRead, buffer.Length);
            return ValueTask.FromResult(count);
        }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
