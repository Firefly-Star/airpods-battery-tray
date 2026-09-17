using System.Text;

namespace AirPodsBleTray;

/// <summary>
/// An Apple 0x07 "proximity pairing" advertisement, i.e. an AirPods battery broadcast.
///
/// The field layout is a 27-byte packed struct, verified against the GNU GPL AirPodsDesktop
/// project (Source/Core/AppleCP.h), where it is declared as a bitfield struct with the same
/// compile-time size assertion. Nothing here is guessed: each offset below maps to a named
/// field there.
///
/// Note there is no left/right in the protocol. Each earbud broadcasts the same structure and
/// says which side it is; the low nibble is always the broadcasting pod and the high nibble is
/// the other one. Reading left/right without that flip is the classic way to get them swapped.
/// </summary>
internal sealed record AirPodsAdvertisement(
    ulong Address,
    int Rssi,
    byte[] Raw,
    ushort ModelId,
    bool BroadcastFromLeft,
    int CurrentLevel,
    int OtherLevel,
    int CaseLevel,
    bool CurrentCharging,
    bool OtherCharging,
    bool CaseCharging,
    bool CurrentInEar,
    bool OtherInEar,
    bool BothInCase,
    bool LidClosed)
{
    public const ushort AppleCompanyId = 0x004C;
    public const byte ProximityPairingType = 0x07;
    public const int PacketLength = 27;

    // Level nibbles run 0..10, i.e. 0%..100% in 10% steps. Anything above 10 means "unavailable",
    // which is not the same as 0%.
    private const int MaximumLevel = 10;

    public int? LeftBattery => AsPercent(BroadcastFromLeft ? CurrentLevel : OtherLevel);

    public int? RightBattery => AsPercent(BroadcastFromLeft ? OtherLevel : CurrentLevel);

    public int? CaseBattery => AsPercent(CaseLevel);

    public bool LeftCharging => BroadcastFromLeft ? CurrentCharging : OtherCharging;

    public bool RightCharging => BroadcastFromLeft ? OtherCharging : CurrentCharging;

    public bool LeftInEar => !LeftCharging && (BroadcastFromLeft ? CurrentInEar : OtherInEar);

    public bool RightInEar => !RightCharging && (BroadcastFromLeft ? OtherInEar : CurrentInEar);

    public string ModelName => ModelId switch
    {
        0x2002 => "AirPods 1",
        0x200F => "AirPods 2",
        0x2013 => "AirPods 3",
        0x200E => "AirPods Pro",
        0x2014 => "AirPods Pro 2",
        0x2024 => "AirPods Pro 2 (USB-C)",
        0x2019 => "AirPods 4",
        0x201B => "AirPods 4 ANC",
        0x2027 => "AirPods Pro 3",
        0x200A => "AirPods Max",
        0x2012 => "Beats Fit Pro",
        _ => $"未知型号 0x{ModelId:X4}",
    };

    public static bool TryParse(ulong address, int rssi, ReadOnlySpan<byte> data, out AirPodsAdvertisement advertisement)
    {
        advertisement = null!;

        if (data.Length != PacketLength || data[0] != ProximityPairingType || data[1] != PacketLength - 2)
        {
            return false;
        }

        ushort modelId = (ushort)(data[3] | (data[4] << 8));

        byte status = data[5];
        bool currentInEar = (status & 0b0000_0010) != 0;
        bool bothInCase = (status & 0b0000_0100) != 0;
        bool otherInEar = (status & 0b0000_1000) != 0;
        bool broadcastFromLeft = (status & 0b0010_0000) != 0;

        byte pods = data[6];
        int currentLevel = pods & 0x0F;
        int otherLevel = pods >> 4;

        byte caseByte = data[7];
        int caseLevel = caseByte & 0x0F;
        bool currentCharging = (caseByte & 0b0001_0000) != 0;
        bool otherCharging = (caseByte & 0b0010_0000) != 0;
        bool caseCharging = (caseByte & 0b0100_0000) != 0;

        bool lidClosed = (data[8] & 0b0000_1000) != 0;

        advertisement = new AirPodsAdvertisement(
            address,
            rssi,
            data.ToArray(),
            modelId,
            broadcastFromLeft,
            currentLevel,
            otherLevel,
            caseLevel,
            currentCharging,
            otherCharging,
            caseCharging,
            currentInEar,
            otherInEar,
            bothInCase,
            lidClosed);

        return true;
    }

    private static int? AsPercent(int level) => level is >= 0 and <= MaximumLevel ? level * 10 : null;

    public string Mac()
    {
        string hex = Address.ToString("X12");
        return string.Join(":", Enumerable.Range(0, 6).Select(i => hex.Substring(i * 2, 2)));
    }

    public string Describe()
    {
        var text = new StringBuilder();

        text.AppendLine($"地址   {Mac()}");
        text.AppendLine($"信号   {Rssi} dBm     广播自 {(BroadcastFromLeft ? "左耳" : "右耳")}");
        text.AppendLine($"型号   {ModelName}");
        text.AppendLine();
        text.AppendLine($"左 {Show(LeftBattery)}{Mark(LeftCharging)}   右 {Show(RightBattery)}{Mark(RightCharging)}   盒 {Show(CaseBattery)}{Mark(CaseCharging)}");
        text.AppendLine($"左耳戴着 {(LeftInEar ? "是" : "否")}   右耳戴着 {(RightInEar ? "是" : "否")}   两只都在盒里 {(BothInCase ? "是" : "否")}   盒盖 {(LidClosed ? "关着" : "开着")}");
        text.AppendLine();
        text.AppendLine("原始字节");
        text.AppendLine(Convert.ToHexString(Raw));

        return text.ToString();
    }

    private static string Show(int? value) => value is null ? "--" : $"{value}%";

    private static string Mark(bool charging) => charging ? " ⚡" : string.Empty;
}
