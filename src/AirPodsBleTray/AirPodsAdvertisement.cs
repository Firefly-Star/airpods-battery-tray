using System.Text;

namespace AirPodsBleTray;

/// <summary>
/// A parsed Apple 0x07 ("AirPods proximity pairing") advertisement.
///
/// The byte offsets below are PROVISIONAL. They come from reverse-engineering write-ups
/// that contradict each other and have not been confirmed against a known battery reading,
/// so the left/right nibble order in particular may be flipped. Raw is kept so the mapping
/// can be corrected without having to capture the traffic again.
/// </summary>
internal sealed record AirPodsAdvertisement(
    ulong Address,
    int Rssi,
    byte[] Raw,
    byte Model,
    byte Status,
    int? Left,
    int? Right,
    int? Case)
{
    public const ushort AppleCompanyId = 0x004C;
    public const byte ProximityPairingType = 0x07;

    // Offsets are counted from the Apple type byte, i.e. Raw[0] is the message type.
    private const int MinimumLength = 11;
    private const int ModelOffset = 3;
    private const int StatusOffset = 4;

    // Confirmed against a known reading: with both buds at 100% this byte reads 0xAA,
    // i.e. an A nibble (100%) per ear. Offset 5 holds something that varies far too much
    // to be a battery level.
    private const int PodsBatteryOffset = 6;

    // Still unverified: the case level byte below has never been checked against a known value.
    private const int CaseBatteryOffset = 8;

    // Status bit 5 selects which pod is primary, which in turn decides which nibble
    // of the pods byte belongs to which ear.
    private const byte PrimaryPodIsLeft = 0x20;

    public static bool TryParse(ulong address, int rssi, ReadOnlySpan<byte> data, out AirPodsAdvertisement advertisement)
    {
        advertisement = null!;

        if (data.Length < MinimumLength || data[0] != ProximityPairingType)
        {
            return false;
        }

        byte status = data[StatusOffset];
        byte pods = data[PodsBatteryOffset];
        byte caseByte = data[CaseBatteryOffset];

        int? upper = DecodeNibble(pods >> 4);
        int? lower = DecodeNibble(pods & 0x0F);

        bool leftIsPrimary = (status & PrimaryPodIsLeft) != 0;

        advertisement = new AirPodsAdvertisement(
            address,
            rssi,
            data.ToArray(),
            data[ModelOffset],
            status,
            leftIsPrimary ? upper : lower,
            leftIsPrimary ? lower : upper,
            DecodeNibble(caseByte >> 4));

        return true;
    }

    // 4-bit levels: 0-9 -> 0-90%, A-E -> 100%, F -> unavailable.
    private static int? DecodeNibble(int nibble) => nibble switch
    {
        0xF => null,
        >= 0xA => 100,
        _ => nibble * 10,
    };

    public string Mac()
    {
        string hex = Address.ToString("X12");
        return string.Join(":", Enumerable.Range(0, 6).Select(i => hex.Substring(i * 2, 2)));
    }

    public string Describe()
    {
        var text = new StringBuilder();

        text.AppendLine($"地址   {Mac()}");
        text.AppendLine($"信号   {Rssi} dBm");
        text.AppendLine($"型号   0x{Model:X2}      状态 0x{Status:X2}");
        text.AppendLine();
        text.AppendLine($"左 {Show(Left)}    右 {Show(Right)}    盒 {Show(Case)}");
        text.AppendLine();
        text.AppendLine("原始字节（从类型字节起）");
        text.AppendLine(Convert.ToHexString(Raw));
        text.AppendLine();
        text.AppendLine("逐字节按 4-bit 解码（0-9=0-90%，A-E=100%，F=无）：");

        for (int i = 3; i < Math.Min(Raw.Length, 11); i++)
        {
            text.AppendLine($"  [{i,2}] 0x{Raw[i]:X2}    高 {Nibble(Raw[i] >> 4),4}    低 {Nibble(Raw[i] & 0x0F),4}");
        }

        return text.ToString();
    }

    private static string Show(int? value) => value is null ? "--" : $"{value}%";

    private static string Nibble(int nibble)
    {
        int? value = DecodeNibble(nibble);
        return value is null ? "无" : $"{value}%";
    }
}
