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
    property string openPlaylist: ""
    property var tracks: []
    property string query: ""
    property var allTracks: []

    FontLoader { id: fraunces; source: "fonts/fraunces.ttf" }
    FontLoader { id: sans; source: "fonts/schibsted.ttf" }

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
    Timer { interval: 900; running: args.indexOf("--library") >= 0; onTriggered: { var i = args.indexOf("--library"); if (args[i + 1] && args[i + 1].indexOf("--") !== 0) showPlaylist(args[i + 1]); else libraryOpen = true } }

    function position() {
        if (!st) return 0
        return st.position + (st.playing ? Math.max(0, (now - fetchedAt) / 1000) : 0)
    }
    function clock(s) { s = Math.max(0, Math.floor(s || 0)); return Math.floor(s / 60) + ":" + ("0" + (s % 60)).slice(-2) }
    function view(id) { if (!st) return null; for (var i = 0; i < st.playlists.length; i++) if (st.playlists[i].id === id) return st.playlists[i]; return null }
    function loadTracks(id) { call("GET", "/tracks?playlist=" + encodeURIComponent(id), null, function (d) { if (d) tracks = d }) }
    function showPlaylist(id) { openPlaylist = id; libraryOpen = true; tracks = []; loadTracks(id) }

    Shortcut { sequence: "1"; onActivated: act("/slot", { slot: "1" }) }
    Shortcut { sequence: "2"; onActivated: act("/slot", { slot: "2" }) }
    Shortcut { sequence: "Space"; onActivated: act("/toggle") }
    Shortcut { sequence: "Right"; onActivated: act("/next") }
    Shortcut { sequence: "Left"; onActivated: act("/prev") }
    Shortcut { sequence: "Escape"; onActivated: { if (openPlaylist) openPlaylist = ""; else if (libraryOpen) libraryOpen = false; else win.close() } }
    Shortcut { sequence: "Ctrl+F"; onActivated: { libraryOpen = true; openPlaylist = ""; search.forceActiveFocus() } }

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

    // ------------------------------------------------------------ wavefronts
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
                    Wavefronts { anchors.centerIn: numeral; width: numeral.height * 1.3; height: numeral.height * 1.3
                        visible: slotItem.lit; tone: slotColor(slotItem.modelData); toRight: slotItem.modelData === "1" }
                    Text {
                        id: numeral
                        anchors { horizontalCenter: parent.horizontalCenter; top: parent.top; topMargin: -height * 0.06 }
                        text: slotItem.modelData
                        font.family: fraunces.name; font.pixelSize: Math.min(slotItem.height * 0.72, slotItem.width * 0.8)
                        font.variableAxes: ({ "opsz": 144, "wght": 300, "SOFT": 100, "WONK": 1 })
                        color: slotItem.lit ? slotColor(slotItem.modelData) : Qt.rgba(0.914, 0.894, 1, st && st.playing ? 0.42 : 0.88)
                        Behavior on color { ColorAnimation { duration: 220 } }
                    }
                    Column {
                        anchors { horizontalCenter: parent.horizontalCenter; top: numeral.bottom; topMargin: -numeral.height * 0.08 }
                        width: parent.width * 0.86; spacing: 2
                        Text { width: parent.width; horizontalAlignment: Text.AlignHCenter; elide: Text.ElideRight
                            text: !slotItem.pv ? "Not set" : (slotItem.pv.name === slotItem.modelData ? slotItem.pv.count + " tracks" : slotItem.pv.name); color: starlight
                            font.family: fraunces.name; font.pixelSize: 26; font.variableAxes: ({ "opsz": 48, "wght": 380, "SOFT": 60 }) }
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
                    MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onClicked: function (e) { if (e.button === Qt.RightButton) { if (slotItem.pv) showPlaylist(slotItem.pv.id) } else act("/slot", { slot: slotItem.modelData }) } }
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
            font.family: fraunces.name; font.pixelSize: 26; font.variableAxes: ({ "opsz": 48, "wght": 380, "SOFT": 60 }) }
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
            property real hover: -1
            onFracChanged: requestPaint(); onWidthChanged: requestPaint()
            property string trackId: nowPlaying.tr ? nowPlaying.tr.id : ""
            onTrackIdChanged: requestPaint()
            onPaint: {
                var c = getContext("2d"); c.reset()
                var mid = height / 2, band = 6, played = width * frac
                c.fillStyle = hairline; c.fillRect(0, mid - 0.5, width, 1)
                var g = c.createLinearGradient(0, 0, width, 0)
                var stops = ["#6B2BFF", blue, "#39D0E0", "#7BE07A", "#FFE15A", "#FF9A3C", red]
                for (var i = 0; i < stops.length; i++) g.addColorStop(i / (stops.length - 1), stops[i])
                c.fillStyle = g; c.fillRect(0, mid - band / 2, played, band)
                var h = 0; for (var j = 0; j < trackId.length; j++) h = (h * 31 + trackId.charCodeAt(j)) % 2147483647
                for (var k = 0; k < 22; k++) {
                    h = (h * 16807) % 2147483647; var x = (h / 2147483647) * width
                    h = (h * 16807) % 2147483647; var a = 0.25 + (h / 2147483647) * 0.75
                    if (x < played) { c.fillStyle = "rgba(0,0,0," + a + ")"; c.fillRect(x, mid - band / 2, 1.6, band) }
                }
                var t = nowPlaying.tone
                c.fillStyle = Qt.rgba(t.r, t.g, t.b, 0.25); c.beginPath(); c.arc(played, mid, 9, 0, Math.PI * 2); c.fill()
                c.fillStyle = starlight; c.beginPath(); c.arc(played, mid, 3.5, 0, Math.PI * 2); c.fill()
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
        Words { anchors.verticalCenter: parent.verticalCenter; text: libraryOpen ? "Close the library" : "All music"; color: dust; font.weight: Font.Medium
            MouseArea { anchors.fill: parent; anchors.margins: -8; cursorShape: Qt.PointingHandCursor; onClicked: { libraryOpen = !libraryOpen; openPlaylist = "" } } }
        Words { anchors { right: parent.right; verticalCenter: parent.verticalCenter }
            color: faint; font.pixelSize: 12
            text: "1 and 2 play, space pauses, arrows skip" }
    }

    // ------------------------------------------------------------ the library drawer
    Rectangle {
        id: drawer
        anchors { top: parent.top; bottom: parent.bottom; right: parent.right }
        width: libraryOpen ? Math.min(440, win.width * 0.46) : 0
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        color: "#05040a"; clip: true
        Rectangle { width: 1; height: parent.height; color: hairline }

        Item {
            anchors.fill: parent; anchors.margins: 24; visible: openPlaylist === ""
            Text { id: libTitle; text: "All music"; color: starlight; font.family: fraunces.name; font.pixelSize: 34; font.variableAxes: ({ "opsz": 48, "wght": 380, "SOFT": 60 }) }
            Words { id: libSub; anchors.top: libTitle.bottom; color: dust; font.pixelSize: 13; text: st ? st.libraryCount + " tracks" : "" }
            Rectangle {
                id: searchBox; anchors { top: libSub.bottom; left: parent.left; right: parent.right; topMargin: 14 }
                height: 40; radius: 12; color: panel; border.color: search.activeFocus ? faint : hairline
                TextInput { id: search; anchors.fill: parent; anchors.margins: 11; color: starlight; font.family: sans.name; font.pixelSize: 15; clip: true
                    onTextChanged: { query = text.toLowerCase(); if (query.length >= 2 && allTracks.length === 0) call("GET", "/tracks?playlist=f%3A", null, function (d) { if (d) allTracks = d }) } }
                Words { anchors.fill: parent; anchors.margins: 11; visible: !search.text; color: faint; text: "Find a song, artist or album" }
            }
            ListView {
                anchors { top: searchBox.bottom; left: parent.left; right: parent.right; bottom: newRow.top; topMargin: 10 }
                clip: true; spacing: 0
                model: query.length >= 2 ? allTracks.filter(function (t) { return (t.title + " " + t.artist + " " + t.album).toLowerCase().indexOf(query) >= 0 }).slice(0, 80)
                                         : (st ? st.playlists : [])
                delegate: Item {
                    required property var modelData
                    property bool isTrack: modelData.title !== undefined
                    width: ListView.view.width; height: isTrack ? 48 : 46
                    property int depth: isTrack || modelData.kind === "custom" || !modelData.path ? 0 : modelData.path.split("/").length - 1
                    Rectangle { anchors.fill: parent; radius: 10; color: rowMouse.containsMouse ? "#0f0d18" : "transparent" }
                    Column {
                        anchors { left: parent.left; right: badge.left; verticalCenter: parent.verticalCenter; leftMargin: 8 + depth * 16 }
                        Words { width: parent.width; text: isTrack ? modelData.title : modelData.name + (modelData.kind === "custom" ? "" : "")
                            font.pixelSize: isTrack ? 15 : (depth === 0 ? 17 : 15); font.family: !isTrack && depth === 0 ? fraunces.name : sans.name }
                        Words { width: parent.width; color: faint; font.pixelSize: 12
                            text: isTrack ? [modelData.artist, modelData.album].filter(function (s) { return s }).join(", ")
                                          : modelData.count + " tracks" + (modelData.kind === "custom" ? ", yours" : "") + (modelData.mode === "order" ? ", in order" : "") }
                    }
                    Rectangle { id: badge; visible: !isTrack && modelData.slot !== ""; width: 26; height: 26; radius: 13; color: "transparent"
                        border.color: slotColor(modelData.slot || "1"); anchors { right: parent.right; verticalCenter: parent.verticalCenter; rightMargin: 6 }
                        Text { anchors.centerIn: parent; text: modelData.slot || ""; color: slotColor(modelData.slot || "1"); font.family: fraunces.name; font.pixelSize: 16 } }
                    MouseArea { id: rowMouse; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                        onClicked: { if (isTrack) { var id = modelData.id; act("/play", { playlist: "f:" + (id.lastIndexOf("/") > 0 ? id.substring(0, id.lastIndexOf("/")) : ""), track: id }) } else showPlaylist(modelData.id) } }
                }
            }
            Pill { id: newRow; anchors { left: parent.left; bottom: parent.bottom }
            label: "New playlist"
                onClicked: hub("POST", "/api/playlists", { name: "New playlist", tracks: [] }, function (r) { if (r && r.playlist) { showPlaylist(r.playlist.id); renameField.text = ""; renameField.forceActiveFocus() } }) }
        }

        Item {
            id: plPage
            anchors.fill: parent; anchors.margins: 24; visible: openPlaylist !== ""
            property var pv: view(openPlaylist)
            Words { id: backLink; text: "All music"; color: dust; font.pixelSize: 13
                MouseArea { anchors.fill: parent; anchors.margins: -6; cursorShape: Qt.PointingHandCursor; onClicked: openPlaylist = "" } }
            Text { id: plTitle; anchors.top: backLink.bottom; anchors.topMargin: 8; width: parent.width; elide: Text.ElideRight
                visible: !(plPage.pv && plPage.pv.kind === "custom" && renameField.activeFocus)
                text: plPage.pv ? plPage.pv.name : ""; color: starlight; font.family: fraunces.name; font.pixelSize: 32; font.variableAxes: ({ "opsz": 48, "wght": 380, "SOFT": 60 })
                MouseArea { anchors.fill: parent; enabled: plPage.pv && plPage.pv.kind === "custom"; onClicked: { renameField.text = plPage.pv.name; renameField.forceActiveFocus() } } }
            TextInput { id: renameField; anchors.top: plTitle.top; width: parent.width; visible: activeFocus; color: starlight
                font.family: fraunces.name; font.pixelSize: 32
                onAccepted: { if (text.trim()) hub("PUT", "/api/playlists/" + openPlaylist, { name: text.trim() }); focus = false } }
            Words { id: plSub; anchors.top: plTitle.bottom; color: dust; font.pixelSize: 13
                text: plPage.pv ? plPage.pv.count + " tracks" + (plPage.pv.kind === "custom" ? ", made by you; click the name to rename" : ", the folder " + plPage.pv.path) : "" }
            Flow {
                id: plActions; anchors { top: plSub.bottom; left: parent.left; right: parent.right; topMargin: 12 }
            spacing: 8
                Pill { label: st && st.playing && st.playlist === openPlaylist ? "Pause" : "Play"; strong: true
                    onClicked: st.playing && st.playlist === openPlaylist ? act("/pause") : act("/play", { playlist: openPlaylist }) }
                Pill { label: "Reshuffle"; onClicked: act("/play", { playlist: openPlaylist, fresh: true }) }
                Pill { label: plPage.pv && plPage.pv.mode === "order" ? "In order" : "Shuffled"
                    onClicked: hub("PUT", "/api/mode", { playlist: openPlaylist, mode: plPage.pv.mode === "order" ? "shuffle" : "order" }) }
                Repeater { model: ["1", "2"]
                    Pill { required property string modelData; property bool on: plPage.pv && plPage.pv.slot === modelData
                        label: on ? "On " + modelData : "Put on " + modelData; strong: on; tone: slotColor(modelData)
                        onClicked: { var b = {}; b[modelData] = openPlaylist; hub("PUT", "/api/slots", b) } } }
                Pill { visible: plPage.pv && plPage.pv.kind === "custom"; label: "Delete"
                    onClicked: { hub("DELETE", "/api/playlists/" + openPlaylist); openPlaylist = "" } }
            }
            ListView {
                id: trackList
                anchors { top: plActions.bottom; left: parent.left; right: parent.right; bottom: parent.bottom; topMargin: 12 }
                clip: true; model: tracks
                delegate: Item {
                    required property var modelData
                    width: ListView.view.width; height: 46
                    property bool current: st && st.track && st.track.id === modelData.id && st.playlist === openPlaylist
                    Rectangle { anchors.fill: parent; radius: 10; color: trMouse.containsMouse ? "#0f0d18" : "transparent" }
                    Rectangle { visible: current; width: 7; height: 7; radius: 3.5; color: starlight; anchors { left: parent.left; leftMargin: 4; verticalCenter: parent.verticalCenter } }
                    Column { anchors { left: parent.left; right: add.left; leftMargin: 20; verticalCenter: parent.verticalCenter }
                        Words { width: parent.width; text: modelData.title }
                        Words { width: parent.width; color: faint; font.pixelSize: 12; text: modelData.artist } }
                    Words { id: add; anchors { right: dur.left; rightMargin: 12; verticalCenter: parent.verticalCenter }
            color: trMouse.containsMouse ? dust : "transparent"; font.pixelSize: 12
                        text: plPage.pv && plPage.pv.kind === "custom" ? "Remove" : "Add to" }
                    Words { id: dur; anchors { right: parent.right; rightMargin: 6; verticalCenter: parent.verticalCenter }
            color: faint; font.pixelSize: 12; text: clock(modelData.duration) }
                    MouseArea { id: trMouse; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                        onClicked: function (e) {
                            if (e.x > add.x - 6 && e.x < add.x + add.width + 6) {
                                if (plPage.pv.kind === "custom") hub("PUT", "/api/playlists/" + openPlaylist, { remove: [modelData.id] }, function () { loadTracks(openPlaylist) })
                                else { addMenu.trackId = modelData.id; addMenu.popup() }
                            } else act("/play", { playlist: openPlaylist, track: modelData.id })
                        } }
                }
            }
        }
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
