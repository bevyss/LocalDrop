using LocalDrop.Core;

namespace LocalDrop.Core.Tests;

public class DiscoveryTests
{
    private readonly Dictionary<string, string> _txt = new()
    {
        ["txtvers"] = "1", ["pv"] = "1", ["id"] = "0123456789abcdef0123456789abcdef",
        ["name"] = "Pixel Phone", ["type"] = "android", ["caps"] = ""
    };

    [Fact]
    public void ParsesSharedDiscoveryVector()
    {
        using var vector = System.Text.Json.JsonDocument.Parse(
            File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "discovery.json")));
        var properties = vector.RootElement.GetProperty("valid").EnumerateObject()
            .ToDictionary(item => item.Name, item => item.Value.GetString()!);
        Assert.Equal(DiscoveryRecord.ServiceType, vector.RootElement.GetProperty("serviceType").GetString());
        Assert.True(DiscoveryRecord.TryParse(properties, 48937, out var record));
        Assert.Equal("Pixel Phone", record!.Name);
        properties["caps"] = "file1";
        Assert.True(DiscoveryRecord.TryParse(properties, 48937, out var capable));
        Assert.Equal("file1", capable!.Capabilities);
    }

    [Theory]
    [InlineData("pv", "2")]
    [InlineData("id", "invalid")]
    [InlineData("name", "")]
    [InlineData("name", "bad\nname")]
    [InlineData("type", "unknown")]
    public void RejectsInvalidMetadata(string key, string value)
    {
        var txt = new Dictionary<string, string>(_txt) { [key] = value };
        Assert.False(DiscoveryRecord.TryParse(txt, 48937, out _));
    }

    [Fact]
    public void CreatesAndPersistsRandomIdentity()
    {
        var directory = Path.Combine(Path.GetTempPath(), "localdrop-test-" + Guid.NewGuid());
        try
        {
            var path = Path.Combine(directory, "device-id");
            var first = DeviceIdentity.LoadOrCreate(path);
            Assert.Matches("^[0-9a-f]{32}$", first);
            Assert.Equal(first, DeviceIdentity.LoadOrCreate(path));
            Assert.NotEqual(first, DiscoveryRecord.NewId());
        }
        finally { Directory.Delete(directory, recursive: true); }
    }
}
