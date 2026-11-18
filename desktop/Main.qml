// The doppler window. It is only a view: the music lives in the background player, which keeps
// going when this closes. Talks to the player at 127.0.0.1:<port> (the first argument after --).
import QtQuick
import QtQuick.Window
import QtQuick.Controls.Basic

Window {
    id: win
    width: 1040; height: 660
    minimumWidth: 640; minimumHeight: 460
    visible: true
    color: "#000000"
    title: "doppler"

    readonly property color starlight: "#E9E4FF"
    readonly property color dust: "#8E8AA8"
    readonly property color faint: "#4A4660"
    readonly property color hairline: "#1E1B2B"
    readonly property color panel: "#0B0A12"
    readonly property color red: "#FF5A3C"
    readonly property color blue: "#5B8CFF"
    function slotColor(s) { return s === "2" ? blue : red }

    property string api: "http://127.0.0.1:" + portArg()
    function portArg() { var a = Qt.application.arguments; for (var i = 0; i < a.length; i++) if (/^[0-9]+$/.test(a[i])) return a[i]; return "4636" }
    property var st: null
    property real fetchedAt: 0
    property real now: Date.now()
    property bool libraryOpen: false
    property string drawerView: "library"   // what the drawer shows under a playlist: "library", "recent" or "listening"
    property string openPlaylist: ""
    property var recentTracks: []
    function loadRecent() { call("GET", "/recent", null, function (d) { if (d) recentTracks = d }) }
    property var listening: null             // {months: [{month, seconds, plays, songs, artists, top}], local}
    property string listenMonth: monthKey(0)
    function loadListening() { call("GET", "/listening", null, function (d) { if (d && d.months) listening = d }) }
    function showDrawer(v) {
        drawerView = v; openPlaylist = ""; libraryOpen = true
        if (v === "recent") loadRecent()
        if (v === "listening") { listenMonth = monthKey(0); loadListening() }
    }
    function closeDrawer() { libraryOpen = false; openPlaylist = "" }
    // Playlists played lately, newest first, the ones on a key left out (they're on the home screen).
    function recentPlaylists() {
        if (!st) return []
        return st.playlists.filter(function (p) { return p.session && p.session.updated && !p.slot })
            .sort(function (a, b) { return b.session.updated - a.session.updated }).slice(0, 6)
    }
    function folderOf(id) { return "f:" + (id.lastIndexOf("/") > 0 ? id.substring(0, id.lastIndexOf("/")) : "") }
    property var tracks: []
    property string query: ""
    property string browse: "folder"  // what All music lists: folders and yours, artists, or albums
    function listed() {
        if (!st) return []
        // On the Artists or Albums tab the search goes through that list alone.
        if (browse !== "folder") return st.playlists.filter(function (p) { return p.kind === browse && (!query || (p.name + " " + p.by).toLowerCase().indexOf(query) >= 0) })
        if (query.length < 2) return st.playlists.filter(function (p) { return p.kind === "folder" || p.kind === "custom" })
        var named = st.playlists.filter(function (p) { return (p.kind === "artist" || p.kind === "album") && (p.name + " " + p.by).toLowerCase().indexOf(query) >= 0 }).slice(0, 8)
        return named.concat(allTracks.filter(function (t) { return (t.title + " " + t.artist + " " + t.album).toLowerCase().indexOf(query) >= 0 }).slice(0, 80))
    }
    property var allTracks: []
    property string find: ""          // the search inside an open playlist
    property string sortBy: "default"
    readonly property var sorts: [["default", ""], ["title", "Title"], ["artist", "Artist"], ["album", "Album"],
                                  ["most", "Most played"], ["least", "Least played"], ["recent", "Recently played"]]
    function sortName(key) {
        if (key === "default") return !plPage.pv ? "File order" : plPage.pv.kind === "custom" ? "Order added" : plPage.pv.kind === "folder" ? "File order" : "Album order"
        for (var i = 0; i < sorts.length; i++) if (sorts[i][0] === key) return sorts[i][1]
        return ""
    }

    FontLoader { id: sans; source: "fonts/space-grotesk.ttf" }

    function call(method, path, body, done) {
        var x = new XMLHttpRequest()
        x.onreadystatechange = function () {
            if (x.readyState !== XMLHttpRequest.DONE) return
            var data = null
            try { data = JSON.parse(x.responseText) } catch (e) {}
            if (done) done(data, x.status)
        }
        x.open(method, api + path)
        if (body !== undefined && body !== null) { x.setRequestHeader("Content-Type", "application/json"); x.send(JSON.stringify(body)) }
        else x.send()
    }
    function take(data) { if (data && data.rev !== undefined) { st = data; fetchedAt = Date.now() } }
    function act(path, body) { call("POST", path, body || {}, function (r) { if (r && r.ok === false && r.error) toast.show(r.error) }) }
    function hub(method, path, body, done) {
        call("POST", "/hub", { method: method, path: path, body: body || null }, function (r) {
            if (r && r.ok === false && r.error) toast.show(r.error)
            if (done) done(r)
        })
    }
    function watch() {
        call("GET", "/wait?timeout=50&rev=" + (st ? st.rev : 0), null, function (d, status) {
            if (d) { take(d); watch() } else retry.start()
        })
    }
    Timer { id: retry; interval: 2000; onTriggered: win.watch() }
    Timer { interval: 250; running: st && st.playing; repeat: true; onTriggered: win.now = Date.now() }
    Component.onCompleted: { call("GET", "/state", null, function (d) { take(d); watch() }) }
    // For checking the look without a screen: qml6 Main.qml -- <port> <png> [library|playlist id]
    property var args: Qt.application.arguments
    Timer {
        interval: 2500; running: args.indexOf("--shot") >= 0
        onTriggered: { win.contentItem.grabToImage(function (r) { r.saveToFile(args[args.indexOf("--shot") + 1]); Qt.quit() }) }
    }
    Timer { interval: 900; running: args.indexOf("--library") >= 0; onTriggered: { var i = args.indexOf("--library"); if (args[i + 1] && args[i + 1].indexOf("--") !== 0) showPlaylist(args[i + 1]); else if (args[i + 1] === "--recent") showDrawer("recent"); else if (args[i + 1] === "--listening") showDrawer("listening"); else libraryOpen = true } }

    function position() {
        if (!st) return 0
        return st.position + (st.playing ? Math.max(0, (now - fetchedAt) / 1000) : 0)
    }
    function counted(n) { return n === 1 ? "1 track" : n + " tracks" }
    // "2026-11" for this month, or `back` months before it.
    function monthKey(back) { var d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - back); return d.getFullYear() + "-" + ("0" + (d.getMonth() + 1)).slice(-2) }
    function monthDate(key) { return new Date(+key.slice(0, 4), +key.slice(5, 7) - 1, 1) }
    function monthOf(key) {
        var none = { month: key, seconds: 0, plays: 0, songs: 0, artists: [], top: [] }
        if (!listening) return none
        for (var i = 0; i < listening.months.length; i++) if (listening.months[i].month === key) return listening.months[i]
        return none
    }
    function hm(s) { var m = Math.floor((s || 0) / 60); return m >= 60 ? Math.floor(m / 60) + " h " + ("0" + (m % 60)).slice(-2) + " m" : m + " m" }
    function clock(s) { s = Math.max(0, Math.floor(s || 0)); return Math.floor(s / 60) + ":" + ("0" + (s % 60)).slice(-2) }
    function view(id) { if (!st) return null; for (var i = 0; i < st.playlists.length; i++) if (st.playlists[i].id === id) return st.playlists[i]; return null }
    function loadTracks(id) { call("GET", "/tracks?playlist=" + encodeURIComponent(id), null, function (d) { if (d) tracks = d }) }
    function showPlaylist(id) { openPlaylist = id; libraryOpen = true; tracks = []; plFind.text = ""; loadTracks(id) }
    property int playsRev: st ? st.playsRev || 0 : 0
    onPlaysRevChanged: {
        if (openPlaylist && (sortBy === "most" || sortBy === "least" || sortBy === "recent")) loadTracks(openPlaylist)
        if (libraryOpen && drawerView === "recent") loadRecent()
        if (libraryOpen && drawerView === "listening") loadListening()
    }
    // A song left the library (deleted here or anywhere else): drop it from the lists on show.
    property int libraryCount: st ? st.libraryCount : 0
    onLibraryCountChanged: {
        if (openPlaylist) loadTracks(openPlaylist)
        if (allTracks.length) call("GET", "/tracks?playlist=f%3A", null, function (d) { if (d) allTracks = d })
        if (libraryOpen && drawerView === "recent") loadRecent()
    }
    function shown() {
        var q = find, list = []
        for (var i = 0; i < tracks.length; i++) {
            var t = tracks[i]
            if (!q || (t.title + " " + t.artist + " " + t.album).toLowerCase().indexOf(q) >= 0) list.push({ t: t, i: i })
        }
        function text(a, b) { return a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" }) }
        var by = {
            title: function (a, b) { return text(a.t.title, b.t.title) },
            artist: function (a, b) { return text(a.t.artist || "\uffff", b.t.artist || "\uffff") || text(a.t.album, b.t.album) },
            album: function (a, b) { return text(a.t.album || "\uffff", b.t.album || "\uffff") },
            most: function (a, b) { return (b.t.plays || 0) - (a.t.plays || 0) },
            least: function (a, b) { return (a.t.plays || 0) - (b.t.plays || 0) },
            recent: function (a, b) { return (b.t.last || 0) - (a.t.last || 0) }
        }[sortBy]
        if (by) list.sort(function (a, b) { return by(a, b) || a.i - b.i })
        return list.map(function (x) { return x.t })
    }
    function ago(when) {
        if (!when) return "Never"
        var days = Math.floor((Date.now() / 1000 - when) / 86400)
        if (days < 1) return "Today"
        if (days < 2) return "Yesterday"
        if (days < 14) return days + " days ago"
        if (days < 60) return Math.floor(days / 7) + " weeks ago"
        if (days < 365) return Math.floor(days / 30) + " months ago"
        return days < 730 ? "A year ago" : Math.floor(days / 365) + " years ago"
    }

    Shortcut { sequence: "1"; onActivated: act("/slot", { slot: "1" }) }
    Shortcut { sequence: "2"; onActivated: act("/slot", { slot: "2" }) }
    Shortcut { sequence: "Space"; onActivated: act("/toggle") }
    Shortcut { sequence: "Right"; onActivated: act("/next") }
    Shortcut { sequence: "Left"; onActivated: act("/prev") }
    Shortcut { sequence: "Escape"; onActivated: { if (confirm.visible) confirm.visible = false; else if (openPlaylist && plFind.text) plFind.text = ""; else if (openPlaylist) openPlaylist = ""; else if (libraryOpen && search.text) search.text = ""; else if (libraryOpen) closeDrawer(); else win.close() } }
    Shortcut { sequence: "Ctrl+F"; onActivated: { if (openPlaylist) plFind.forceActiveFocus(); else { showDrawer("library"); search.forceActiveFocus() } } }

    // ------------------------------------------------------------ the sky
    Canvas {
        anchors.fill: parent
        onPaint: {
            var c = getContext("2d"); c.reset()
            var seed = 314
            function r() { seed = (seed * 16807) % 2147483647; return seed / 2147483647 }
            for (var i = 0; i < 260; i++) {
                var x = r() * width, y = r() * height, s = r(), k = r()
                var tint = k < 0.07 ? "255,90,60" : (k < 0.14 ? "91,140,255" : "233,228,255")
                c.fillStyle = "rgba(" + tint + "," + (0.1 + r() * 0.5) + ")"
                c.beginPath(); c.arc(x, y, s * s * 1.4 + 0.35, 0, Math.PI * 2); c.fill()
            }
        }
        onWidthChanged: requestPaint(); onHeightChanged: requestPaint()
    }

    component Words: Text { color: starlight; font.family: sans.name; font.pixelSize: 15; elide: Text.ElideRight }
    component Pill: Rectangle {
        id: pill
        property string label; property bool strong: false; property color tone: starlight
        signal clicked()
        implicitWidth: pillText.implicitWidth + 30; implicitHeight: 36; radius: 18
        color: strong ? Qt.rgba(tone.r, tone.g, tone.b, 0.14) : (pillMouse.containsMouse ? "#14121e" : "transparent")
        border.color: strong ? Qt.rgba(tone.r, tone.g, tone.b, 0.7) : hairline; border.width: 1
        Text { id: pillText; anchors.centerIn: parent; text: pill.label; color: pill.strong ? pill.tone : starlight; font.family: sans.name; font.pixelSize: 14; font.weight: Font.Medium }
        MouseArea { id: pillMouse; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: pill.clicked() }
    }

    // A round button with a drawn mark: close, back or more.
    component IconButton: Rectangle {
        id: ib
        property string mark: "close"; property string tip: ""
        signal clicked()
        width: 36; height: 36; radius: 18
        color: ibMouse.containsMouse ? "#16131f" : "transparent"
        border.color: ibMouse.containsMouse ? faint : "transparent"
        Accessible.role: Accessible.Button; Accessible.name: tip
        Canvas {
            anchors.centerIn: parent; width: 14; height: 14
            property bool hot: ibMouse.containsMouse
            onHotChanged: requestPaint()
            onPaint: {
                var c = getContext("2d"); c.reset()
                c.strokeStyle = hot ? starlight : dust; c.fillStyle = c.strokeStyle; c.lineWidth = 1.6; c.lineCap = "round"
                if (ib.mark === "close") { c.beginPath(); c.moveTo(2, 2); c.lineTo(12, 12); c.moveTo(12, 2); c.lineTo(2, 12); c.stroke() }
                else if (ib.mark === "back") { c.beginPath(); c.moveTo(9, 1.5); c.lineTo(3.5, 7); c.lineTo(9, 12.5); c.stroke() }
                else for (var i = 0; i < 3; i++) { c.beginPath(); c.arc(2 + i * 5, 7, 1.5, 0, Math.PI * 2); c.fill() }
            }
        }
        MouseArea { id: ibMouse; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: ib.clicked() }
        ToolTip.visible: tip !== "" && ibMouse.containsMouse; ToolTip.text: tip; ToolTip.delay: 600
    }

    // One song in any list. Click plays it; right-click or the dots open its menu.
    component TrackRow: Item {
        id: row
        property var track
        property bool current: false
        property string note: ""
        property string playlist: ""   // the playlist it's listed in, "" for search and recent
        signal play()
        width: ListView.view ? ListView.view.width : 300; height: 48
        Rectangle { anchors.fill: parent; radius: 10; color: rowArea.containsMouse ? "#0f0d18" : "transparent" }
        Rectangle { visible: row.current; width: 7; height: 7; radius: 3.5; color: starlight; anchors { left: parent.left; leftMargin: 6; verticalCenter: parent.verticalCenter } }
        Column { anchors { left: parent.left; right: noteText.left; leftMargin: 22; rightMargin: 10; verticalCenter: parent.verticalCenter }
            Words { width: parent.width; text: row.track ? row.track.title : "" }
            Words { width: parent.width; color: faint; font.pixelSize: 12
                text: row.track ? [row.track.artist, row.track.album].filter(function (s) { return s }).join(", ") : "" } }
        Words { id: noteText; anchors { right: more.left; rightMargin: 4; verticalCenter: parent.verticalCenter }
            color: faint; font.pixelSize: 12; text: row.note }
        MouseArea { id: rowArea; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; acceptedButtons: Qt.LeftButton | Qt.RightButton
            onClicked: function (e) { if (e.button === Qt.RightButton) trackMenu.open_(row.track, row.playlist); else row.play() } }
        IconButton { id: more; mark: "more"; tip: "More"; anchors { right: parent.right; verticalCenter: parent.verticalCenter }
            opacity: rowArea.containsMouse || trackMenu.visible && trackMenu.track === row.track ? 1 : 0.35
            onClicked: trackMenu.open_(row.track, row.playlist) }
    }

    // ------------------------------------------------------------ wavefronts
    // The 1 and the 2 (and the other digits, for the hours on Listening) as stars on a 5 by 7 grid,
    // the same grid as the phone's Dots.kt. While lit, the
    // stars twinkle out of step; the timer only runs then, so a closed or idle window costs nothing.
    component DotNumeral: Canvas {
        id: dn
        property string digit: "1"
        property color tone: starlight
        property bool lit: false
        property real phase: 0
        readonly property var glyphs: ({
            "0": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
            "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
            "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
            "3": [".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."],
            "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
            "5": ["#####", "#....", "#....", "####.", "....#", "#...#", ".###."],
            "6": ["..###", ".#...", "#....", "####.", "#...#", "#...#", ".###."],
            "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
            "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
            "9": [".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."] })
        width: height * 5 / 7
        onToneChanged: requestPaint(); onLitChanged: requestPaint(); onDigitChanged: requestPaint(); onPhaseChanged: requestPaint()
        Timer { running: dn.lit && dn.visible; repeat: true; interval: 50; onTriggered: dn.phase = (dn.phase + 0.098) % (Math.PI * 2) }
        onPaint: {
            var ctx = getContext("2d"); ctx.reset()
            var pitch = height / 7, rows = glyphs[digit] || glyphs["1"]
            for (var r = 0; r < 7; r++) for (var c = 0; c < 5; c++) {
                var x = (c + 0.5) * pitch, y = (r + 0.5) * pitch
                if (rows[r][c] === "#") {
                    var tw = lit ? 0.82 + 0.18 * Math.sin(phase + c * 1.7 + r * 2.3) : 1
                    if (lit) { ctx.fillStyle = Qt.rgba(tone.r, tone.g, tone.b, 0.12 * tw); ctx.beginPath(); ctx.arc(x, y, pitch * 0.5, 0, Math.PI * 2); ctx.fill() }
                    ctx.fillStyle = Qt.rgba(tone.r, tone.g, tone.b, tone.a * tw); ctx.beginPath(); ctx.arc(x, y, pitch * 0.33, 0, Math.PI * 2); ctx.fill()
                } else if (height > 40) {
                    ctx.fillStyle = Qt.rgba(0.29, 0.275, 0.376, 0.45); ctx.beginPath(); ctx.arc(x, y, pitch * 0.07, 0, Math.PI * 2); ctx.fill()
                }
            }
        }
    }

    component Wavefronts: Canvas {
        id: waves
        property color tone: red
        property bool toRight: true
        property real phase: 0
        NumberAnimation on phase { from: 0; to: 1; duration: 2600; loops: Animation.Infinite; running: waves.visible }
        onPhaseChanged: requestPaint()
        onPaint: {
            var c = getContext("2d"); c.reset()
            var m = Math.min(width, height), n = 6
            for (var i = 0; i < n; i++) {
                var p = ((i / n) + phase) % 1
                var r = m * 0.16 + p * m * 0.34
                var cx = width / 2 - (toRight ? 1 : -1) * m * 0.2 * p
                c.strokeStyle = Qt.rgba(tone.r, tone.g, tone.b, (1 - p) * 0.55); c.lineWidth = 1.3
                c.beginPath(); c.arc(cx, height / 2, r, 0, Math.PI * 2); c.stroke()
            }
        }
    }

    // ------------------------------------------------------------ the two keys
    Item {
        id: home
        anchors { left: parent.left; right: drawer.left; top: parent.top; bottom: nowPlaying.top; margins: 28 }
        Words {
            id: status
            anchors { left: parent.left; top: parent.top }
            color: dust; font.pixelSize: 13
            text: !st ? "Starting the player" : (st.message ? st.message : (!st.hub.url ? "Not synced: no hub set" : (!st.hub.online ? "Offline. Picks up the sync when the hub is back" : "")))
        }
        Rectangle {
            visible: st && st.elsewhere && st.elsewhere.playing
            anchors { right: parent.right; top: parent.top }
            height: 44; width: elseRow.implicitWidth + 28; radius: 16; color: panel; border.color: hairline
            Row {
                id: elseRow; anchors.centerIn: parent; spacing: 14
                Words { anchors.verticalCenter: parent.verticalCenter; color: dust; font.pixelSize: 13
                    text: st && st.elsewhere ? st.elsewhere.device + " is playing " + (st.elsewhere.track ? st.elsewhere.track.title : st.elsewhere.name) : "" }
                Pill { label: "Play here"; onClicked: act("/play", { playlist: st.elsewhere.playlist }) }
            }
        }
        Row {
            anchors { left: parent.left; right: parent.right; top: status.bottom; bottom: parent.bottom; topMargin: 8 }
            Repeater {
                model: ["1", "2"]
                delegate: Item {
                    id: slotItem
                    required property string modelData
                    property var pv: st ? view(st.slots[modelData]) : null
                    property bool lit: st && st.playing && pv && st.playlist === pv.id
                    width: parent.width / 2; height: parent.height
                    // The numeral plays; the name under it opens the playlist (so does a right-click anywhere).
                    MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onClicked: function (e) { if (e.button === Qt.RightButton) { if (slotItem.pv) showPlaylist(slotItem.pv.id) } else act("/slot", { slot: slotItem.modelData }) } }
                    Wavefronts { anchors.centerIn: numeral; width: numeral.height * 1.3; height: numeral.height * 1.3
                        visible: slotItem.lit; tone: slotColor(slotItem.modelData); toRight: slotItem.modelData === "1" }
                    DotNumeral {
                        id: numeral
                        anchors { horizontalCenter: parent.horizontalCenter; top: parent.top; topMargin: slotItem.height * 0.06 }
                        digit: slotItem.modelData; lit: slotItem.lit
                        height: Math.min(slotItem.height * 0.56, slotItem.width * 0.7)
                        tone: slotItem.lit ? slotColor(slotItem.modelData) : Qt.rgba(0.914, 0.894, 1, st && st.playing ? 0.42 : 0.88)
                    }
                    Column {
                        anchors { horizontalCenter: parent.horizontalCenter; top: numeral.bottom; topMargin: numeral.height * 0.1 }
                        width: parent.width * 0.86; spacing: 2
                        Text { id: slotName; anchors.horizontalCenter: parent.horizontalCenter; width: Math.min(implicitWidth, parent.width)
                            horizontalAlignment: Text.AlignHCenter; elide: Text.ElideRight
                            text: !slotItem.pv ? "Not set" : (slotItem.pv.name === slotItem.modelData ? slotItem.pv.count + " tracks" : slotItem.pv.name); color: starlight
                            font.family: sans.name; font.pixelSize: 26; font.weight: Font.Medium; font.underline: nameMouse.containsMouse
                            MouseArea { id: nameMouse; anchors.fill: parent; anchors.margins: -6; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                                enabled: !!slotItem.pv; onClicked: showPlaylist(slotItem.pv.id) }
                            ToolTip.visible: nameMouse.containsMouse; ToolTip.text: "Open the playlist"; ToolTip.delay: 600 }
                        Words { width: parent.width; horizontalAlignment: Text.AlignHCenter; font.pixelSize: 13
                            color: slotItem.lit ? slotColor(slotItem.modelData) : dust
                            text: {
                                var p = slotItem.pv
                                if (!p) return "Pick one in All music"
                                if (slotItem.lit) return "Playing"
                                if (!p.session) return "Shuffled, from the top"
                                if (p.session.playing && p.session.device !== st.device) return "Playing on " + p.session.device
                                if (p.session.device !== st.device) return "Left off on " + p.session.device + (p.session.track ? ": " + p.session.track.title : "")
                                return "Picks up at " + (p.session.track ? p.session.track.title : "where you left off")
                            } }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ now playing
    Item {
        id: nowPlaying
        anchors { left: parent.left; right: drawer.left; bottom: footer.top; leftMargin: 28; rightMargin: 28 }
        height: st && st.track ? 118 : 0
        visible: height > 0
        property var tr: st ? st.track : null
        property real dur: tr && tr.duration ? tr.duration : 0
        property color tone: { if (!st) return starlight; for (var k in st.slots) if (st.slots[k] === st.playlist) return slotColor(k); return starlight }
        Text { id: npTitle; anchors { left: parent.left; right: controls.left; top: parent.top; rightMargin: 20 }
            elide: Text.ElideRight
            text: nowPlaying.tr ? nowPlaying.tr.title : ""; color: starlight
            font.family: sans.name; font.pixelSize: 26; font.weight: Font.Medium
            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; enabled: st && st.playlist; onClicked: showPlaylist(st.playlist) } }
        Words { anchors { left: parent.left; right: controls.left; top: npTitle.bottom; rightMargin: 20 }
            color: dust; font.pixelSize: 14
            text: nowPlaying.tr ? [nowPlaying.tr.artist, st.playlistName].filter(function (s) { return s }).join(", from ") : "" }
        Row {
            id: controls; anchors { right: parent.right; top: parent.top; topMargin: 4 }
            spacing: 10
            Pill { label: "Previous"; onClicked: act("/prev") }
            Pill { label: st && st.playing ? "Pause" : "Play"; strong: true; tone: nowPlaying.tone; onClicked: act("/toggle") }
            Pill { label: "Next"; onClicked: act("/next") }
        }
        Canvas {
            id: spectrum
            anchors { left: parent.left; right: parent.right; bottom: times.top; bottomMargin: 2 }
            height: 30
            property real frac: nowPlaying.dur > 0 ? Math.min(1, position() / nowPlaying.dur) : 0
            onFracChanged: requestPaint(); onWidthChanged: requestPaint()
            property color tone: nowPlaying.tone
            onToneChanged: requestPaint()
            property string trackId: nowPlaying.tr ? nowPlaying.tr.id : ""
            onTrackIdChanged: requestPaint()
            onPaint: {
                // One solid line in the playing key's colour, like everything else that's lit.
                var c = getContext("2d"); c.reset()
                var mid = height / 2, band = 4, played = width * frac, t = nowPlaying.tone
                c.fillStyle = hairline; c.fillRect(0, mid - band / 2, width, band)
                c.fillStyle = t; c.fillRect(0, mid - band / 2, played, band)
                c.fillStyle = Qt.rgba(t.r, t.g, t.b, 0.22); c.beginPath(); c.arc(played, mid, 9, 0, Math.PI * 2); c.fill()
                c.fillStyle = t; c.beginPath(); c.arc(played, mid, 5, 0, Math.PI * 2); c.fill()
            }
            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor
                onClicked: function (e) { if (nowPlaying.dur > 0) act("/seek", { position: e.x / width * nowPlaying.dur }) } }
        }
        Item {
            id: times; anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
            height: 18
            Words { anchors.left: parent.left; color: faint; font.pixelSize: 12; text: clock(position()) }
            Words { anchors.right: parent.right; color: faint; font.pixelSize: 12; text: clock(nowPlaying.dur) }
        }
    }

    Item {
        id: footer
        anchors { left: parent.left; right: drawer.left; bottom: parent.bottom; leftMargin: 28; rightMargin: 28 }
        height: 48
        Row {
            anchors.verticalCenter: parent.verticalCenter; spacing: 8
            Repeater {
                model: [["library", "All music"], ["recent", "Recent"], ["listening", "Listening"]]
                // A second press on the open one closes the drawer.
                Pill { required property var modelData; label: modelData[1]
                    strong: libraryOpen && !openPlaylist && drawerView === modelData[0]
                    onClicked: { if (strong) closeDrawer(); else showDrawer(modelData[0]) } }
            }
        }
        Words { anchors { right: parent.right; verticalCenter: parent.verticalCenter }
            color: faint; font.pixelSize: 12
            text: "1 and 2 play, space pauses, arrows skip" }
    }

    // ------------------------------------------------------------ the library drawer
    Rectangle {
        id: drawer
        anchors { top: parent.top; bottom: parent.bottom; right: parent.right }
        width: libraryOpen ? Math.min(460, win.width * 0.48) : 0
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        color: "#05040a"; clip: true
        Rectangle { width: 1; height: parent.height; color: hairline }

        // Back (inside a playlist) on the left, close on the right, on every page.
        Item {
            id: drawerBar
            anchors { top: parent.top; left: parent.left; right: parent.right; margins: 16 }
            height: 36
            IconButton { mark: "back"; tip: drawerView === "recent" ? "Back to Recent" : drawerView === "listening" ? "Back to Listening" : "Back to All music"
                visible: openPlaylist !== ""
                onClicked: openPlaylist = "" }
            IconButton { id: plMore; anchors.right: closeButton.left; anchors.rightMargin: 4; mark: "more"; tip: "More"; visible: openPlaylist !== ""
                onClicked: plMenu.popup(plMore, plMore.width - 220, plMore.height + 4) }
            IconButton { id: closeButton; anchors.right: parent.right; mark: "close"; tip: "Close (Esc)"; onClicked: closeDrawer() }
        }

        Item {
            anchors { top: drawerBar.bottom; left: parent.left; right: parent.right; bottom: parent.bottom; margins: 24; topMargin: 4 }
            visible: openPlaylist === "" && drawerView === "library"
            Text { id: libTitle; text: "All music"; color: starlight; font.family: sans.name; font.pixelSize: 34; font.weight: Font.Medium }
            Words { id: libSub; anchors.top: libTitle.bottom; color: dust; font.pixelSize: 13; text: st ? counted(st.libraryCount) : "" }
            Rectangle {
                id: searchBox; anchors { top: libSub.bottom; left: parent.left; right: parent.right; topMargin: 14 }
                height: 40; radius: 12; color: panel; border.color: search.activeFocus ? faint : hairline
                TextInput { id: search; anchors.fill: parent; anchors.margins: 11; color: starlight; font.family: sans.name; font.pixelSize: 15; clip: true
                    onTextChanged: { query = text.toLowerCase(); if (query.length >= 2 && allTracks.length === 0) call("GET", "/tracks?playlist=f%3A", null, function (d) { if (d) allTracks = d }) } }
                Words { anchors.fill: parent; anchors.margins: 11; visible: !search.text; color: faint
                    text: browse === "artist" ? "Find an artist" : browse === "album" ? "Find an album" : "Find a song, artist or album" }
            }
            Row {
                id: browseRow; anchors { top: searchBox.bottom; left: parent.left; topMargin: 10 }
                spacing: 8
                Repeater {
                    model: [["folder", "Playlists"], ["artist", "Artists"], ["album", "Albums"]]
                    Pill { required property var modelData; label: modelData[1]; strong: browse === modelData[0]; onClicked: browse = modelData[0] }
                }
            }
            ListView {
                anchors { top: browseRow.bottom; left: parent.left; right: parent.right; bottom: newRow.top; topMargin: 10; bottomMargin: 10 }
                clip: true; spacing: 0
                model: { query; browse; return listed() }
                delegate: Loader {
                    required property var modelData
                    width: ListView.view.width
                    sourceComponent: modelData.title !== undefined ? songRow : listRow
                    Component { id: songRow
                        TrackRow { track: modelData; current: st && st.track && st.track.id === modelData.id
                            onPlay: act("/play", { playlist: folderOf(modelData.id), track: modelData.id }) } }
                    Component { id: listRow
                        PlaylistRow { pv: modelData } }
                }
            }
            Pill { id: newRow; anchors { left: parent.left; bottom: parent.bottom }
                label: "New playlist"
                onClicked: hub("POST", "/api/playlists", { name: "New playlist", tracks: [] }, function (r) { if (r && r.playlist) { showPlaylist(r.playlist.id); renameField.text = ""; renameField.forceActiveFocus() } }) }
        }

        Item {
            id: recentPage
            anchors { top: drawerBar.bottom; left: parent.left; right: parent.right; bottom: parent.bottom; margins: 24; topMargin: 4 }
            visible: openPlaylist === "" && drawerView === "recent"
            property var lists: { st; return recentPlaylists() }
            Text { id: recentTitle; text: "Recent"; color: starlight; font.family: sans.name; font.pixelSize: 34; font.weight: Font.Medium }
            ListView {
                anchors { top: recentTitle.bottom; left: parent.left; right: parent.right; bottom: parent.bottom; topMargin: 10 }
                clip: true
                header: Column {
                    width: ListView.view.width
                    Words { visible: recentPage.lists.length > 0; color: dust; font.pixelSize: 13; font.weight: Font.Medium; text: "Playlists"; bottomPadding: 4 }
                    Repeater { model: recentPage.lists
                        PlaylistRow { required property var modelData; pv: modelData; width: parent.width } }
                    Words { visible: recentTracks.length > 0; color: dust; font.pixelSize: 13; font.weight: Font.Medium; text: "Songs"
                        topPadding: recentPage.lists.length ? 16 : 0; bottomPadding: 4 }
                    Words { visible: recentTracks.length === 0 && recentPage.lists.length === 0; color: dust; text: "Nothing played yet" }
                }
                model: recentTracks
                delegate: TrackRow { required property var modelData; track: modelData; note: ago(modelData.last)
                    current: st && st.track && st.track.id === modelData.id
                    onPlay: act("/play", { playlist: folderOf(modelData.id), track: modelData.id }) }
            }
        }

        // How much was heard in a month, and the months before it as columns of stars.
        Item {
            id: listenPage
            anchors { top: drawerBar.bottom; left: parent.left; right: parent.right; bottom: parent.bottom; margins: 24; topMargin: 4 }
            visible: openPlaylist === "" && drawerView === "listening"
            property var m: { listening; return monthOf(listenMonth) }
            property bool hours: m.seconds >= 3600
            property string amount: String(Math.floor(m.seconds / (hours ? 3600 : 60)))
            Text { id: listenTitle; text: "Listening"; color: starlight; font.family: sans.name; font.pixelSize: 34; font.weight: Font.Medium }
            Flickable {
                anchors { top: listenTitle.bottom; left: parent.left; right: parent.right; bottom: parent.bottom; topMargin: 18 }
                clip: true; contentHeight: listenCol.height; boundsBehavior: Flickable.StopAtBounds
                Column {
                    id: listenCol
                    width: parent.width
                    Row {
                        spacing: 22
                        Row {
                            spacing: 14
                            Repeater { model: listenPage.amount.split("")
                                DotNumeral { required property string modelData; digit: modelData; height: 98; tone: starlight } }
                        }
                        Words { anchors.bottom: parent.bottom; anchors.bottomMargin: -4; color: dust; font.pixelSize: 17
                            text: listenPage.hours ? (listenPage.amount === "1" ? "hour" : "hours") : (listenPage.amount === "1" ? "minute" : "minutes") }
                    }
                    Words { topPadding: 14; width: parent.width; color: dust; font.pixelSize: 13
                        text: monthDate(listenMonth).toLocaleDateString(Qt.locale(), "MMMM yyyy")
                            + (listenPage.m.plays ? ", " + listenPage.m.plays + (listenPage.m.plays === 1 ? " play" : " plays") : "")
                            + (listenPage.m.songs ? ", " + listenPage.m.songs + (listenPage.m.songs === 1 ? " song" : " songs") : "")
                            + (listening && listening.local ? ", this computer only" : "") }
                    Item { width: 1; height: 22 }
                    Canvas {
                        id: chart
                        width: parent.width; height: 128
                        property var keys: { var k = []; for (var i = 11; i >= 0; i--) k.push(monthKey(i)); return k }
                        property var values: { listening; return keys.map(function (k) { return monthOf(k).seconds }) }
                        readonly property int rows: 9
                        property int hover: -1
                        onValuesChanged: requestPaint(); onWidthChanged: requestPaint(); onHoverChanged: requestPaint()
                        Connections { target: win; function onListenMonthChanged() { chart.requestPaint() } }
                        onPaint: {
                            var c = getContext("2d"); c.reset()
                            var col = width / 12, pitch = Math.min(col * 0.5, height / rows), top = Math.max.apply(null, values)
                            for (var i = 0; i < 12; i++) {
                                var v = values[i], lit = v > 0 && top > 0 ? Math.max(1, Math.round(v / top * rows)) : 0
                                var on = keys[i] === listenMonth, x = (i + 0.5) * col
                                for (var r = 0; r < rows; r++) {
                                    var y = height - (r + 0.5) * pitch
                                    if (r < lit) {
                                        var t = on ? starlight : dust, a = on ? 1 : (i === hover ? 0.85 : 0.55)
                                        c.fillStyle = Qt.rgba(t.r, t.g, t.b, a); c.beginPath(); c.arc(x, y, pitch * 0.33, 0, Math.PI * 2); c.fill()
                                    } else {
                                        c.fillStyle = Qt.rgba(0.29, 0.275, 0.376, 0.45); c.beginPath(); c.arc(x, y, pitch * 0.07, 0, Math.PI * 2); c.fill()
                                    }
                                }
                            }
                        }
                        MouseArea { anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                            function at(x) { return Math.max(0, Math.min(11, Math.floor(x / (width / 12)))) }
                            onPositionChanged: function (e) { chart.hover = at(e.x) }
                            onExited: chart.hover = -1
                            onClicked: function (e) { listenMonth = chart.keys[at(e.x)] } }
                        ToolTip.visible: hover >= 0; ToolTip.delay: 300
                        ToolTip.text: hover >= 0 ? monthDate(keys[hover]).toLocaleDateString(Qt.locale(), "MMMM") + ": " + hm(values[hover]) : ""
                    }
                    Row {
                        width: parent.width
                        Repeater { model: chart.keys
                            Words { required property string modelData; width: parent.width / 12; horizontalAlignment: Text.AlignHCenter
                                font.pixelSize: 11; color: modelData === listenMonth ? starlight : faint
                                text: monthDate(modelData).toLocaleDateString(Qt.locale(), "MMM") } }
                    }
                    Words { visible: listenPage.m.artists.length > 0; topPadding: 26; bottomPadding: 4; color: dust; font.pixelSize: 13; font.weight: Font.Medium; text: "Artists" }
                    Repeater { model: listenPage.m.artists
                        Item {
                            required property var modelData
                            property var pv: { if (!st) return null; var n = modelData[0].toLowerCase()
                                for (var i = 0; i < st.playlists.length; i++) if (st.playlists[i].kind === "artist" && st.playlists[i].name.toLowerCase() === n) return st.playlists[i]
                                return null }
                            width: parent.width; height: 40
                            Rectangle { anchors.fill: parent; radius: 10; color: artistMouse.containsMouse && parent.pv ? "#0f0d18" : "transparent" }
                            Words { anchors { left: parent.left; right: artistTime.left; leftMargin: 8; rightMargin: 10; verticalCenter: parent.verticalCenter }
                                text: modelData[0] }
                            Words { id: artistTime; anchors { right: parent.right; rightMargin: 10; verticalCenter: parent.verticalCenter }
                                color: faint; font.pixelSize: 12; text: hm(modelData[1]) }
                            MouseArea { id: artistMouse; anchors.fill: parent; hoverEnabled: true; enabled: !!parent.pv; cursorShape: Qt.PointingHandCursor
                                onClicked: showPlaylist(parent.pv.id) }
                        } }
                    Words { visible: listenPage.m.top.length > 0; topPadding: 16; bottomPadding: 4; color: dust; font.pixelSize: 13; font.weight: Font.Medium; text: "Songs" }
                    Repeater { model: listenPage.m.top
                        TrackRow { required property var modelData; width: listenCol.width; track: modelData; note: hm(modelData.seconds)
                            current: st && st.track && st.track.id === modelData.id
                            onPlay: act("/play", { playlist: folderOf(modelData.id), track: modelData.id }) } }
                    Words { visible: listening !== null && listenPage.m.seconds === 0; topPadding: 26; color: dust
                        text: "Nothing heard in " + monthDate(listenMonth).toLocaleDateString(Qt.locale(), "MMMM") }
                }
            }
        }

        Item {
            id: plPage
            anchors { top: drawerBar.bottom; left: parent.left; right: parent.right; bottom: parent.bottom; margins: 24; topMargin: 4 }
            visible: openPlaylist !== ""
            property var pv: view(openPlaylist)
            property bool custom: !!pv && pv.kind === "custom"
            Text { id: plTitle; width: parent.width; elide: Text.ElideRight
                visible: !(plPage.custom && renameField.activeFocus)
                text: plPage.pv ? plPage.pv.name : ""; color: starlight; font.family: sans.name; font.pixelSize: 32; font.weight: Font.Medium
                MouseArea { anchors.fill: parent; enabled: plPage.custom; cursorShape: plPage.custom ? Qt.IBeamCursor : Qt.ArrowCursor
                    onClicked: { renameField.text = plPage.pv.name; renameField.forceActiveFocus() } } }
            TextInput { id: renameField; anchors.top: plTitle.top; width: parent.width; visible: activeFocus; color: starlight
                font.family: sans.name; font.pixelSize: 32; font.weight: Font.Medium
                onAccepted: { if (text.trim()) hub("PUT", "/api/playlists/" + openPlaylist, { name: text.trim() }); focus = false } }
            Words { id: plSub; anchors.top: plTitle.bottom; color: dust; font.pixelSize: 13; width: parent.width
                text: !plPage.pv ? "" : counted(plPage.pv.count) + (plPage.pv.mode === "order" ? ", in order" : ", shuffled")
                    + (plPage.custom ? ", made by you" : plPage.pv.kind === "folder" ? ", the folder " + plPage.pv.path : plPage.pv.by ? ", by " + plPage.pv.by : "") }
            Flow {
                id: plActions; anchors { top: plSub.bottom; left: parent.left; right: parent.right; topMargin: 12 }
                spacing: 8
                Pill { property bool here: st && st.playing && st.playlist === openPlaylist
                    label: here ? "Pause" : "Play"; strong: true
                    tone: plPage.pv && plPage.pv.slot ? slotColor(plPage.pv.slot) : starlight
                    onClicked: { if (here) act("/toggle"); else act("/play", { playlist: openPlaylist }) } }
                Pill { label: plPage.pv && plPage.pv.mode === "order" ? "From the top" : "Reshuffle"; onClicked: act("/play", { playlist: openPlaylist, fresh: true }) }
                Repeater { model: ["1", "2"]
                    Pill { required property string modelData; property bool on: plPage.pv && plPage.pv.slot === modelData
                        label: on ? "On key " + modelData : "Put on key " + modelData; strong: on; tone: slotColor(modelData)
                        onClicked: { if (on) return; var b = {}; b[modelData] = openPlaylist; hub("PUT", "/api/slots", b) } } }
            }
            Rectangle {
                id: plFindBox; anchors { top: plActions.bottom; left: parent.left; right: sortPill.left; topMargin: 14; rightMargin: 8 }
                height: 36; radius: 12; color: panel; border.color: plFind.activeFocus ? faint : hairline
                TextInput { id: plFind; anchors.fill: parent; anchors.margins: 9; anchors.leftMargin: 11; color: starlight; font.family: sans.name; font.pixelSize: 14; clip: true
                    onTextChanged: find = text.trim().toLowerCase() }
                Words { anchors.fill: parent; anchors.margins: 9; anchors.leftMargin: 11; visible: !plFind.text; color: faint; font.pixelSize: 14; text: "Find in " + (plPage.pv ? plPage.pv.name : "this playlist") }
            }
            Pill { id: sortPill; anchors { right: parent.right; verticalCenter: plFindBox.verticalCenter }
                label: sortName(sortBy); onClicked: sortMenu.popup(sortPill, 0, sortPill.height + 4) }
            Words { id: noMatch; anchors { top: plFindBox.bottom; left: parent.left; topMargin: 16; leftMargin: 4 }
                visible: find !== "" && trackList.count === 0; color: dust; text: "Nothing here matches that" }
            ListView {
                id: trackList
                anchors { top: plFindBox.bottom; left: parent.left; right: parent.right; bottom: parent.bottom; topMargin: 8 }
                clip: true; model: { find; sortBy; return shown() }
                delegate: TrackRow {
                    required property var modelData
                    track: modelData; playlist: openPlaylist
                    current: st && st.track && st.track.id === modelData.id && st.playlist === openPlaylist
                    note: sortBy === "most" || sortBy === "least" ? (modelData.plays ? modelData.plays + (modelData.plays === 1 ? " play" : " plays") : "Not played")
                        : sortBy === "recent" ? ago(modelData.last) : clock(modelData.duration)
                    onPlay: act("/play", { playlist: openPlaylist, track: modelData.id })
                }
            }
        }
    }

    // A playlist, artist or album in a list: its name, what's in it, and its key if it has one.
    component PlaylistRow: Item {
        id: prow
        property var pv
        width: ListView.view ? ListView.view.width : parent.width; height: 48
        property int depth: !pv || pv.kind !== "folder" || !pv.path ? 0 : pv.path.split("/").length - 1
        Rectangle { anchors.fill: parent; radius: 10; color: prowMouse.containsMouse ? "#0f0d18" : "transparent" }
        Column {
            anchors { left: parent.left; right: badge.left; verticalCenter: parent.verticalCenter; leftMargin: 8 + prow.depth * 16 }
            Words { width: parent.width; text: prow.pv ? prow.pv.name : ""
                font.pixelSize: prow.depth === 0 ? 17 : 15; font.weight: prow.depth === 0 ? Font.Medium : Font.Normal }
            Words { width: parent.width; color: st && st.playing && prow.pv && st.playlist === prow.pv.id ? slotColor(prow.pv.slot || "1") : faint; font.pixelSize: 12
                text: !prow.pv ? "" : st && st.playing && st.playlist === prow.pv.id ? "Playing"
                    : (prow.pv.by ? prow.pv.by + ", " : "") + counted(prow.pv.count) + (prow.pv.kind === "custom" ? ", yours" : "")
                      + (prow.pv.mode === "order" ? ", in order" : "") }
        }
        Rectangle { id: badge; visible: !!prow.pv && prow.pv.slot !== ""; width: 26; height: 26; radius: 13; color: "transparent"
            border.color: slotColor(prow.pv && prow.pv.slot || "1"); anchors { right: parent.right; verticalCenter: parent.verticalCenter; rightMargin: 6 }
            DotNumeral { anchors.centerIn: parent; height: 12; digit: prow.pv && prow.pv.slot || "1"; tone: slotColor(prow.pv && prow.pv.slot || "1") } }
        MouseArea { id: prowMouse; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: showPlaylist(prow.pv.id) }
    }

    // Everything you can do to one song.
    Menu {
        id: trackMenu
        property var track: null
        property string playlist: ""
        property bool custom: { var p = view(playlist); return !!p && p.kind === "custom" }
        function open_(t, pl) { track = t; playlist = pl || ""; popup() }
        background: Rectangle { implicitWidth: 230; color: panel; border.color: hairline; radius: 10 }
        MenuItem { contentItem: Text { text: "Play"; color: starlight; font.family: sans.name; font.pixelSize: 14 }
            background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
            onTriggered: act("/play", { playlist: trackMenu.playlist || folderOf(trackMenu.track.id), track: trackMenu.track.id }) }
        MenuItem { contentItem: Text { text: "Add to playlist"; color: starlight; font.family: sans.name; font.pixelSize: 14 }
            background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
            onTriggered: { addMenu.trackId = trackMenu.track.id; addMenu.popup() } }
        MenuItem { visible: trackMenu.custom; height: visible ? implicitHeight : 0
            contentItem: Text { text: "Remove from this playlist"; color: starlight; font.family: sans.name; font.pixelSize: 14 }
            background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
            onTriggered: hub("PUT", "/api/playlists/" + trackMenu.playlist, { remove: [trackMenu.track.id] }, function () { loadTracks(openPlaylist) }) }
        MenuItem { contentItem: Text { text: "Delete from library"; color: red; font.family: sans.name; font.pixelSize: 14 }
            background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
            onTriggered: {
                var t = trackMenu.track
                confirm.ask("Delete " + t.title + "?", "The file goes to the hub's trash, and off every device.", "Delete", function () {
                    hub("POST", "/api/tracks/delete", { tracks: [t.id] }, function (r) { if (r && r.ok) toast.show("Deleted " + t.title) })
                })
            } }
    }

    Menu {
        id: plMenu
        background: Rectangle { implicitWidth: 220; color: panel; border.color: hairline; radius: 10 }
        MenuItem { contentItem: Text { text: plPage.pv && plPage.pv.mode === "order" ? "Shuffle it" : "Play in order"; color: starlight; font.family: sans.name; font.pixelSize: 14 }
            background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
            onTriggered: hub("PUT", "/api/mode", { playlist: openPlaylist, mode: plPage.pv.mode === "order" ? "shuffle" : "order" }) }
        MenuItem { visible: plPage.custom; height: visible ? implicitHeight : 0
            contentItem: Text { text: "Rename"; color: starlight; font.family: sans.name; font.pixelSize: 14 }
            background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
            onTriggered: { renameField.text = plPage.pv.name; renameField.forceActiveFocus() } }
        MenuItem { visible: plPage.custom; height: visible ? implicitHeight : 0
            contentItem: Text { text: "Delete playlist"; color: red; font.family: sans.name; font.pixelSize: 14 }
            background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
            onTriggered: {
                var id = openPlaylist, name = plPage.pv.name
                confirm.ask("Delete " + name + "?", "The songs stay in your library.", "Delete", function () { hub("DELETE", "/api/playlists/" + id); openPlaylist = "" })
            } }
    }

    Menu {
        id: addMenu
        property string trackId
        background: Rectangle { implicitWidth: 220; color: panel; border.color: hairline; radius: 10 }
        Instantiator {
            model: st ? st.playlists.filter(function (p) { return p.kind === "custom" }) : []
            delegate: MenuItem { required property var modelData; text: modelData.name
                contentItem: Text { text: modelData.name; color: starlight; font.family: sans.name; font.pixelSize: 14 }
                background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
                onTriggered: hub("PUT", "/api/playlists/" + modelData.id, { add: [addMenu.trackId] }) }
            onObjectAdded: function (i, o) { addMenu.insertItem(i, o) }
            onObjectRemoved: function (i, o) { addMenu.removeItem(o) }
        }
        MenuItem { contentItem: Text { text: "A new playlist"; color: dust; font.family: sans.name; font.pixelSize: 14 }
            background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
            onTriggered: hub("POST", "/api/playlists", { name: "New playlist", tracks: [addMenu.trackId] }) }
    }

    Menu {
        id: sortMenu
        background: Rectangle { implicitWidth: 200; color: panel; border.color: hairline; radius: 10 }
        Repeater {
            model: sorts
            MenuItem { required property var modelData
                contentItem: Text { text: sortName(modelData[0]); color: sortBy === modelData[0] ? starlight : dust; font.family: sans.name; font.pixelSize: 14
                    font.weight: sortBy === modelData[0] ? Font.Medium : Font.Normal }
                background: Rectangle { color: parent.highlighted ? "#16131f" : "transparent" }
                onTriggered: { sortBy = modelData[0]; if (modelData[0] !== "default") loadTracks(openPlaylist) } }
        }
    }

    // "Are you sure?" for anything that can't be taken back from here.
    Rectangle {
        id: confirm
        property var onYes: null
        function ask(title, body, ok, yes) { confirmTitle.text = title; confirmBody.text = body; confirmOk.label = ok; onYes = yes; visible = true; confirmOk.forceActiveFocus() }
        anchors.fill: parent; visible: false; color: Qt.rgba(0, 0, 0, 0.6); z: 10
        MouseArea { anchors.fill: parent; onClicked: confirm.visible = false }
        Rectangle {
            anchors.centerIn: parent; width: Math.min(420, parent.width - 60); height: confirmCol.implicitHeight + 48
            radius: 20; color: panel; border.color: hairline
            MouseArea { anchors.fill: parent }
            Column {
                id: confirmCol; anchors { left: parent.left; right: parent.right; top: parent.top; margins: 24 }
                spacing: 8
                Text { id: confirmTitle; width: parent.width; wrapMode: Text.Wrap; color: starlight; font.family: sans.name; font.pixelSize: 22; font.weight: Font.Medium }
                Words { id: confirmBody; width: parent.width; wrapMode: Text.Wrap; color: dust }
                Item { width: 1; height: 10 }
                Row {
                    anchors.right: parent.right; spacing: 8
                    Pill { label: "Cancel"; onClicked: confirm.visible = false }
                    Pill { id: confirmOk; strong: true; tone: red
                        Keys.onReturnPressed: clicked(); Keys.onEnterPressed: clicked()
                        onClicked: { confirm.visible = false; if (confirm.onYes) confirm.onYes() } }
                }
            }
        }
    }

    Rectangle {
        id: toast
        function show(t) { toastText.text = t; opacity = 1; toastTimer.restart() }
        anchors { horizontalCenter: parent.horizontalCenter; bottom: parent.bottom; bottomMargin: 70 }
        width: toastText.implicitWidth + 32; height: 40; radius: 20; color: panel; border.color: hairline; opacity: 0
        Behavior on opacity { NumberAnimation { duration: 160 } }
        Words { id: toastText; anchors.centerIn: parent }
        Timer { id: toastTimer; interval: 3500; onTriggered: toast.opacity = 0 }
    }
}
