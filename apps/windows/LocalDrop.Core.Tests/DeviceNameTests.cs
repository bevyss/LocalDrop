using LocalDrop.Core;

namespace LocalDrop.Core.Tests;

public class DeviceNameTests
{
    [Fact]
    public void PersistsNormalizedNameAndRejectsInvalidInput()
    {
        var directory = Path.Combine(Path.GetTempPath(), "localdrop-name-" + Guid.NewGuid().ToString("N"));
        var path = Path.Combine(directory, "device-name");
        try
        {
            Assert.Equal("Default PC", DeviceName.LoadOrDefault(path, "Default PC"));
            DeviceName.Save(path, "  Living Room PC  ");
            Assert.Equal("Living Room PC", DeviceName.LoadOrDefault(path, "Default PC"));
            Assert.Throws<ArgumentException>(() => DeviceName.Save(path, "bad\nname"));
            Assert.Throws<ArgumentException>(() => DeviceName.Save(path, new string('x', 65)));
            Assert.Equal("Living Room PC", DeviceName.LoadOrDefault(path, "Default PC"));
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }
}
