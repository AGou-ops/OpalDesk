import os

/// Centralized `os.Logger` categories for OpalDeskCore and OpalDeskMac.
public enum OpalDeskLog {
    private static let subsystem = "io.github.lengsukq.opaldesk.mac"

    public static let session = Logger(subsystem: subsystem, category: "session")
    public static let video = Logger(subsystem: subsystem, category: "video")
    public static let audio = Logger(subsystem: subsystem, category: "audio")
    public static let adb = Logger(subsystem: subsystem, category: "adb")
    public static let trust = Logger(subsystem: subsystem, category: "trust")
    public static let clipboard = Logger(subsystem: subsystem, category: "clipboard")
    public static let file = Logger(subsystem: subsystem, category: "file")
    public static let popout = Logger(subsystem: subsystem, category: "popout")
    public static let app = Logger(subsystem: subsystem, category: "app")
}
