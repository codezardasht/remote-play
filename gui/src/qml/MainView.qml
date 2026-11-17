import QtQuick
import QtQuick.Layouts
import QtQuick.Controls
import QtQuick.Controls.impl
import QtQuick.Controls.Material
import QtQuick.Shapes

import org.streetpea.chiaking

Item {
    id: consolePane

    readonly property int cardWidth: Math.round(Math.min(320, Math.max(220, width * 0.22)))
    readonly property int cardGap: 34
    readonly property int visibleHostCount: {
        let n = 0;
        const hosts = Chiaki.hosts;
        for (let i = 0; i < hosts.length; ++i)
            if (hosts[i].display)
                ++n;
        return n;
    }
    readonly property var currentHost: hostsView.currentItem && hostsView.currentItem.visible ? hostsView.currentItem.host : null

    StackView.onActivated: {
        forceActiveFocus(Qt.TabFocusReason);
        if(!Chiaki.autoConnect && !root.initialAsk && !Chiaki.window.directStream)
        {
            root.initialAsk = true;
            if(Chiaki.settings.addSteamShortcutAsk && (typeof Chiaki.createSteamShortcut === "function"))
                root.showRemindDialog(qsTr("Official Steam artwork + controller layout"), qsTr("Would you like to either create a new non-Steam game for chiaki-ng\nor update an existing non-Steam game with the official artwork and controller layout?") + "\n\n" + qsTr("(Note: If you select no now and want to do this later, click the button or press R3 from the main menu.)"), false, () => root.showSteamShortcutDialog(true));
            else if(Chiaki.settings.remotePlayAsk)
            {
                if(!Chiaki.settings.psnRefreshToken || !Chiaki.settings.psnAuthToken || !Chiaki.settings.psnAuthTokenExpiry || !Chiaki.settings.psnAccountId)
                    root.showRemindDialog(qsTr("Remote Play via PSN"), qsTr("Would you like to connect to PSN?\nThis enables:\n- Automatic registration\n- Playing outside of your home network without port forwarding?") + "\n\n" + qsTr("(Note: If you select no now and want to do this later, go to the Config section of the settings.)"), true, () => root.showPSNTokenDialog(false));
                else
                    Chiaki.settings.remotePlayAsk = false;
            }
        }
    }

    function selectPrevious() {
        if (!hostsView.currentItem || !hostsView.currentItem.visible)
            return;
        hostsView.decrementCurrentIndex();
        while (!hostsView.currentItem.visible)
            hostsView.decrementCurrentIndex();
    }

    function selectNext() {
        if (!hostsView.currentItem || !hostsView.currentItem.visible)
            return;
        hostsView.incrementCurrentIndex();
        while (!hostsView.currentItem.visible)
            hostsView.incrementCurrentIndex();
    }

    function stateText(host) {
        if (!host)
            return "";
        if (host.duid)
            return host.discovered ? qsTr("Automatic Registration Available") : qsTr("Remote Connection via PSN");
        if (host.state == "ready")
            return qsTr("Ready");
        if (host.state == "standby")
            return qsTr("Rest Mode");
        return host.state ? host.state.charAt(0).toUpperCase() + host.state.slice(1) : qsTr("Unknown");
    }

    function stateColor(host) {
        if (!host)
            return "#7f8ba3";
        if (host.duid)
            return "#5fb3ff";
        if (host.state == "ready")
            return "#3ddc84";
        if (host.state == "standby")
            return "#ffb74d";
        return "#7f8ba3";
    }

    Keys.onUpPressed: selectPrevious()
    Keys.onLeftPressed: selectPrevious()
    Keys.onDownPressed: selectNext()
    Keys.onRightPressed: selectNext()
    Keys.onMenuPressed: settingsButton.clicked()
    Keys.onReturnPressed: if (hostsView.currentItem) hostsView.currentItem.connectToHost()
    Keys.onYesPressed: if (hostsView.currentItem) hostsView.currentItem.wakeUpHost()
    Keys.onNoPressed: if (hostsView.currentItem) hostsView.currentItem.deleteHost()
    Keys.onEscapePressed: root.showConfirmDialog(qsTr("Quit"), qsTr("Are you sure you want to quit?"), () => Qt.quit())
    Keys.onPressed: (event) => {
        if (event.modifiers)
            return;
        switch (event.key) {
        case Qt.Key_PageUp:
            if (hostsView.currentItem) hostsView.currentItem.setConsolePin();
            event.accepted = true;
            break;
        case Qt.Key_PageDown:
            if (Chiaki.settings.psnAuthToken) Chiaki.refreshPsnToken();
            event.accepted = true;
            break;
        case Qt.Key_F1:
            if (typeof Chiaki.createSteamShortcut === "function") root.showSteamShortcutDialog(false);
            event.accepted = true;
            break;
        case Qt.Key_F2:
            root.showManualHostDialog();
            event.accepted = true;
            break;
        }
    }

    // ───────────────────────── Top bar ─────────────────────────
    Item {
        id: topBar
        anchors {
            top: parent.top
            left: parent.left
            right: parent.right
            topMargin: 24
            leftMargin: 40
            rightMargin: 40
        }
        height: 64

        Row {
            anchors.verticalCenter: parent.verticalCenter
            spacing: 16

            Image {
                anchors.verticalCenter: parent.verticalCenter
                source: "images/logo.svg"
                sourceSize: Qt.size(112, 112)
                width: 56
                height: 56
                smooth: true
                mipmap: true
                fillMode: Image.PreserveAspectFit
            }

            Column {
                anchors.verticalCenter: parent.verticalCenter
                spacing: 0
                Text {
                    text: "chiaki-ng"
                    color: "#ffffff"
                    font.pixelSize: 28
                    font.weight: Font.Light
                    font.letterSpacing: 1
                }
                Text {
                    text: qsTr("Remote Play")
                    color: "#9fb4d8"
                    font.pixelSize: 15
                    font.letterSpacing: 2
                    font.capitalization: Font.AllUppercase
                }
            }
        }

        Row {
            anchors {
                right: parent.right
                verticalCenter: parent.verticalCenter
            }
            spacing: 18

            PSButton {
                anchors.verticalCenter: parent.verticalCenter
                text: qsTr("Steam Shortcut")
                glyph: "qrc:/icons/l3.svg"
                implicitHeight: 48
                font.pixelSize: 16
                focusPolicy: Qt.NoFocus
                visible: typeof Chiaki.createSteamShortcut === "function"
                onClicked: root.showSteamShortcutDialog(false)
            }

            PSButton {
                anchors.verticalCenter: parent.verticalCenter
                text: qsTr("Refresh PSN")
                glyph: "qrc:/icons/r1.svg"
                implicitHeight: 48
                font.pixelSize: 16
                focusPolicy: Qt.NoFocus
                visible: Chiaki.settings.psnAuthToken
                onClicked: Chiaki.refreshPsnToken()
            }

            PSIconButton {
                tip: qsTr("Add Manual Host")
                icon.source: "qrc:/icons/add-24px.svg"
                glyph: "qrc:/icons/r3.svg"
                onClicked: root.showManualHostDialog()
            }

            PSIconButton {
                id: settingsButton
                tip: qsTr("Settings")
                icon.source: "qrc:/icons/settings-20px.svg"
                onClicked: root.showSettingsDialog()
            }

            PSIconButton {
                tip: qsTr("Quit")
                iconText: "⏻"
                onClicked: Qt.quit()
            }
        }
    }

    // ───────────────────────── Heading ─────────────────────────
    Column {
        id: heading
        anchors {
            top: topBar.bottom
            left: parent.left
            leftMargin: 40
            topMargin: Math.max(16, consolePane.height * 0.04)
        }
        spacing: 4
        Text {
            text: qsTr("Select a console")
            color: "#ffffff"
            font.pixelSize: 34
            font.weight: Font.Light
        }
        Text {
            text: consolePane.visibleHostCount
                  ? qsTr("%n console(s) available", "", consolePane.visibleHostCount)
                  : (Chiaki.discoveryEnabled ? qsTr("Searching your network…") : qsTr("Discovery is off"))
            color: "#9fb4d8"
            font.pixelSize: 17
        }
    }

    // ───────────────────────── Console carousel ─────────────────────────
    ListView {
        id: hostsView
        anchors {
            top: heading.bottom
            left: parent.left
            right: parent.right
            topMargin: 24
        }
        height: Math.round(consolePane.cardWidth * 1.08) + 40
        orientation: ListView.Horizontal
        keyNavigationWraps: true
        clip: false
        interactive: contentWidth > width
        boundsBehavior: Flickable.StopAtBounds
        model: Chiaki.hosts
        highlightRangeMode: ListView.ApplyRange
        preferredHighlightBegin: 40
        preferredHighlightEnd: width - 40
        highlightMoveDuration: 380
        highlightMoveVelocity: -1
        leftMargin: 40
        rightMargin: 40
        cacheBuffer: 2000

        add: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 350; easing.type: Easing.OutCubic }
                NumberAnimation { property: "y"; from: 40; duration: 450; easing.type: Easing.OutCubic }
            }
        }
        displaced: Transition {
            NumberAnimation { properties: "x"; duration: 350; easing.type: Easing.OutCubic }
        }

        onCountChanged: {
            if(!hostsView.currentItem)
                hostsView.incrementCurrentIndex();
            if(!hostsView.currentItem)
                return;
            if(!hostsView.currentItem.visible)
            {
                for(var i = 0; i < hostsView.count; i++)
                {
                    hostsView.incrementCurrentIndex()
                    if(hostsView.currentItem.visible)
                    {
                        break;
                    }
                }
            }
        }

        delegate: Item {
            id: delegate
            required property var modelData
            required property int index
            readonly property var host: modelData
            readonly property bool isCurrent: ListView.isCurrentItem
            visible: modelData.display
            width: modelData.display ? consolePane.cardWidth + consolePane.cardGap : 0
            height: hostsView.height

            function connectToHost() {
                if(modelData.discovered)
                    Chiaki.connectToHost(index, modelData.name);
                else
                    Chiaki.connectToHost(index);
            }

            function wakeUpHost() {
                if(!modelData.discovered && !modelData.duid)
                    Chiaki.wakeUpHost(index);
            }

            function deleteHost() {
                if (modelData.manual)
                    root.showConfirmDialog(qsTr("Delete Console"), qsTr("Are you sure you want to delete this console?"), () => {Chiaki.deleteHost(index)});
                else if (modelData.discovered && !modelData.registered)
                    root.showConfirmDialog(qsTr("Hide Console"), qsTr("Are you sure you want to hide this console?") + "\n\n" + qsTr("Note: You can unhide from the Consoles section of the Settings under Hidden Consoles"), () => Chiaki.hideHost(modelData.mac, modelData.name));
            }

            function setConsolePin() {
                root.showConsolePinDialog(index);
            }

            Item {
                id: card
                width: consolePane.cardWidth
                height: Math.round(consolePane.cardWidth * 1.08)
                anchors.verticalCenter: parent.verticalCenter
                scale: delegate.isCurrent ? 1.0 : (cardMouse.containsMouse ? 0.93 : 0.88)
                opacity: delegate.isCurrent ? 1.0 : 0.62
                transformOrigin: Item.Center
                Behavior on scale { NumberAnimation { duration: 320; easing.type: Easing.OutCubic } }
                Behavior on opacity { NumberAnimation { duration: 320; easing.type: Easing.OutCubic } }

                // focus glow
                Rectangle {
                    anchors.fill: parent
                    anchors.margins: -9
                    radius: 30
                    color: "transparent"
                    border.width: 3
                    border.color: "#ffffff"
                    opacity: delegate.isCurrent ? 0.95 : 0.0
                    Behavior on opacity { NumberAnimation { duration: 260 } }
                }
                Rectangle {
                    anchors.fill: parent
                    anchors.margins: -16
                    radius: 36
                    color: "transparent"
                    border.width: 6
                    border.color: "#5fb3ff"
                    opacity: delegate.isCurrent ? 0.18 : 0.0
                    Behavior on opacity { NumberAnimation { duration: 260 } }
                }

                Rectangle {
                    id: cardBg
                    anchors.fill: parent
                    radius: 22
                    clip: true
                    gradient: Gradient {
                        GradientStop { position: 0.0; color: delegate.isCurrent ? "#2a4d8f" : "#1a3466" }
                        GradientStop { position: 1.0; color: delegate.isCurrent ? "#10244d" : "#0c1a3a" }
                    }
                    border.width: 1
                    border.color: Qt.rgba(1, 1, 1, 0.12)

                    // sheen
                    Rectangle {
                        anchors.fill: parent
                        radius: parent.radius
                        gradient: Gradient {
                            GradientStop { position: 0.0; color: Qt.rgba(1, 1, 1, 0.10) }
                            GradientStop { position: 0.5; color: Qt.rgba(1, 1, 1, 0.0) }
                        }
                    }

                    // spotlight behind the console artwork
                    Shape {
                        id: spotlight
                        anchors.centerIn: consoleImage
                        width: parent.width
                        height: width
                        opacity: delegate.isCurrent ? 0.9 : 0.55
                        Behavior on opacity { NumberAnimation { duration: 300 } }
                        ShapePath {
                            strokeWidth: -1
                            fillGradient: RadialGradient {
                                centerX: spotlight.width / 2; centerY: spotlight.height / 2
                                centerRadius: spotlight.width / 2
                                focalX: centerX; focalY: centerY
                                GradientStop { position: 0.0; color: "#80d8ecff" }
                                GradientStop { position: 0.45; color: "#305fb3ff" }
                                GradientStop { position: 1.0; color: "#005fb3ff" }
                            }
                            startX: 0; startY: 0
                            PathLine { x: spotlight.width; y: 0 }
                            PathLine { x: spotlight.width; y: spotlight.height }
                            PathLine { x: 0; y: spotlight.height }
                            PathLine { x: 0; y: 0 }
                        }
                    }

                    Image {
                        id: consoleImage
                        anchors {
                            top: parent.top
                            horizontalCenter: parent.horizontalCenter
                            topMargin: parent.height * 0.08
                        }
                        width: parent.width * 0.62
                        height: parent.height * 0.52
                        fillMode: Image.PreserveAspectFit
                        source: "image://svg/console-ps" + (modelData.ps5 ? "5" : "4") + (modelData.state == "standby" ? "#light_standby" : "#light_on")
                        sourceSize: Qt.size(width, height)
                        smooth: true
                        mipmap: true
                    }

                    Text {
                        id: platformLabel
                        anchors {
                            top: consoleImage.bottom
                            horizontalCenter: parent.horizontalCenter
                            topMargin: 6
                        }
                        text: modelData.ps5 ? "PS5" : "PS4"
                        color: "#9fb4d8"
                        font.pixelSize: 14
                        font.letterSpacing: 3
                        font.weight: Font.DemiBold
                    }

                    Text {
                        id: nameLabel
                        anchors {
                            top: platformLabel.bottom
                            left: parent.left
                            right: parent.right
                            margins: 16
                            topMargin: 4
                        }
                        horizontalAlignment: Text.AlignHCenter
                        elide: Text.ElideRight
                        text: modelData.name || (Chiaki.settings.streamerMode ? qsTr("Console") : modelData.address) || qsTr("Console")
                        color: "#ffffff"
                        font.pixelSize: 22
                        font.weight: Font.Medium
                    }

                    Row {
                        anchors {
                            top: nameLabel.bottom
                            horizontalCenter: parent.horizontalCenter
                            topMargin: 10
                        }
                        spacing: 8
                        Rectangle {
                            anchors.verticalCenter: parent.verticalCenter
                            width: 10
                            height: 10
                            radius: 5
                            color: consolePane.stateColor(modelData)
                        }
                        Text {
                            text: consolePane.stateText(modelData)
                            color: "#d6e2f5"
                            font.pixelSize: 15
                        }
                    }
                }

                MouseArea {
                    id: cardMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        if (delegate.isCurrent)
                            delegate.connectToHost();
                        else
                            hostsView.currentIndex = delegate.index;
                    }
                    onDoubleClicked: delegate.connectToHost()
                }
            }
        }
    }

    // ───────────────────────── Empty state ─────────────────────────
    Column {
        anchors.centerIn: hostsView
        spacing: 20
        opacity: consolePane.visibleHostCount ? 0.0 : 1.0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 300 } }

        BusyIndicator {
            anchors.horizontalCenter: parent.horizontalCenter
            running: parent.visible && Chiaki.discoveryEnabled
            visible: Chiaki.discoveryEnabled
            Material.accent: "#ffffff"
            width: 64
            height: 64
        }
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: qsTr("No consoles found")
            color: "#ffffff"
            font.pixelSize: 26
            font.weight: Font.Light
        }
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            horizontalAlignment: Text.AlignHCenter
            text: qsTr("Make sure your console is turned on and on the same network,\nor add it manually.")
            color: "#9fb4d8"
            font.pixelSize: 16
        }
        PSButton {
            anchors.horizontalCenter: parent.horizontalCenter
            text: qsTr("Add Manual Host")
            primary: true
            focusPolicy: Qt.NoFocus
            onClicked: root.showManualHostDialog()
        }
    }

    // ───────────────────────── Details of selected console ─────────────────────────
    Item {
        id: details
        anchors {
            top: hostsView.bottom
            left: parent.left
            right: parent.right
            bottom: bottomBar.top
            leftMargin: 40
            rightMargin: 40
            topMargin: 8
        }
        opacity: consolePane.currentHost ? 1.0 : 0.0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 250 } }

        readonly property var host: consolePane.currentHost

        ColumnLayout {
            id: detailsColumn
            anchors {
                top: parent.top
                left: parent.left
                right: parent.right
            }
            spacing: 14

            RowLayout {
                Layout.fillWidth: true
                spacing: 16

                PSButton {
                    text: details.host && details.host.duid && details.host.discovered ? qsTr("Register") : qsTr("Start")
                    primary: true
                    glyph: root.controllerButton("cross")
                    focusPolicy: Qt.NoFocus
                    implicitHeight: 58
                    font.pixelSize: 21
                    onClicked: if (hostsView.currentItem) hostsView.currentItem.connectToHost()
                }

                PSButton {
                    text: qsTr("Wake Up")
                    glyph: root.controllerButton("pyramid")
                    focusPolicy: Qt.NoFocus
                    visible: details.host && details.host.registered && !details.host.duid && !details.host.discovered
                    onClicked: if (hostsView.currentItem) hostsView.currentItem.wakeUpHost()
                }

                PSButton {
                    text: qsTr("Console PIN")
                    glyph: "qrc:/icons/l1.svg"
                    focusPolicy: Qt.NoFocus
                    visible: details.host && details.host.registered
                    onClicked: if (hostsView.currentItem) hostsView.currentItem.setConsolePin()
                }

                PSButton {
                    text: details.host && details.host.manual ? qsTr("Delete") : qsTr("Hide")
                    glyph: root.controllerButton("box")
                    focusPolicy: Qt.NoFocus
                    visible: details.host && (details.host.manual || (details.host.discovered && !details.host.registered))
                    onClicked: if (hostsView.currentItem) hostsView.currentItem.deleteHost()
                }

                Item { Layout.fillWidth: true }
            }

            Flow {
                Layout.fillWidth: true
                spacing: 10

                Repeater {
                    model: {
                        const h = details.host;
                        if (!h)
                            return [];
                        const hidden = Chiaki.settings.streamerMode;
                        let chips = [];
                        if (h.address)
                            chips.push(qsTr("Address  %1").arg(hidden ? "hidden" : h.address));
                        if (h.mac)
                            chips.push(qsTr("ID  %1").arg(hidden ? "hidden" : h.mac));
                        if (!h.duid)
                            chips.push(h.registered ? qsTr("Registered") : qsTr("Unregistered"));
                        if (!h.duid)
                            chips.push(h.discovered ? (h.manual ? qsTr("Discovered + Manual") : qsTr("Discovered")) : qsTr("Manual"));
                        if (!h.duid && h.discovered && h.app)
                            chips.push(qsTr("Playing  %1").arg(h.app));
                        if (!h.duid && h.discovered && h.titleId)
                            chips.push(qsTr("Title ID  %1").arg(h.titleId));
                        return chips;
                    }
                    delegate: Rectangle {
                        required property string modelData
                        height: 34
                        width: chipText.implicitWidth + 28
                        radius: 17
                        color: Qt.rgba(1, 1, 1, 0.07)
                        border.width: 1
                        border.color: Qt.rgba(1, 1, 1, 0.10)
                        Text {
                            id: chipText
                            anchors.centerIn: parent
                            text: modelData
                            color: "#c9d6ee"
                            font.pixelSize: 14
                        }
                    }
                }
            }
        }
    }

    // ───────────────────────── Bottom bar ─────────────────────────
    Item {
        id: bottomBar
        anchors {
            left: parent.left
            right: parent.right
            bottom: parent.bottom
            leftMargin: 40
            rightMargin: 40
            bottomMargin: 20
        }
        height: 56

        Rectangle {
            anchors {
                left: parent.left
                right: parent.right
                top: parent.top
            }
            height: 1
            color: Qt.rgba(1, 1, 1, 0.10)
        }

        Button {
            id: discoveryToggle
            anchors {
                left: parent.left
                verticalCenter: parent.verticalCenter
                verticalCenterOffset: 6
            }
            hoverEnabled: true
            focusPolicy: Qt.NoFocus
            checkable: true
            checked: Chiaki.discoveryEnabled
            onToggled: Chiaki.discoveryEnabled = !Chiaki.discoveryEnabled
            implicitHeight: 40
            leftPadding: 12
            rightPadding: 16
            contentItem: Row {
                spacing: 8
                IconImage {
                    anchors.verticalCenter: parent.verticalCenter
                    width: 22
                    height: 22
                    sourceSize: Qt.size(22, 22)
                    source: "qrc:/icons/discover-" + (discoveryToggle.checked ? "" : "off-") + "24px.svg"
                    color: discoveryToggle.checked ? "#5fb3ff" : "#7f8ba3"
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: discoveryToggle.checked ? qsTr("Discovery On") : qsTr("Discovery Off")
                    color: discoveryToggle.hovered ? "#ffffff" : "#c9d6ee"
                    font.pixelSize: 15
                }
            }
            background: Rectangle {
                radius: height / 2
                color: discoveryToggle.hovered ? Qt.rgba(1, 1, 1, 0.12) : Qt.rgba(1, 1, 1, 0.05)
                Behavior on color { ColorAnimation { duration: 150 } }
            }
        }

        Row {
            anchors {
                horizontalCenter: parent.horizontalCenter
                verticalCenter: parent.verticalCenter
                verticalCenterOffset: 6
            }
            spacing: 26
            visible: consolePane.width > 1100

            Repeater {
                model: [
                    { g: root.controllerButton("cross"), t: qsTr("Start") },
                    { g: root.controllerButton("pyramid"), t: qsTr("Wake Up") },
                    { g: root.controllerButton("box"), t: qsTr("Delete / Hide") },
                    { g: root.controllerButton("moon"), t: qsTr("Quit") }
                ]
                delegate: Row {
                    required property var modelData
                    spacing: 8
                    Image {
                        anchors.verticalCenter: parent.verticalCenter
                        width: 24
                        height: 24
                        sourceSize: Qt.size(24, 24)
                        source: modelData.g
                    }
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.t
                        color: "#9fb4d8"
                        font.pixelSize: 14
                    }
                }
            }
        }

        Text {
            anchors {
                right: parent.right
                verticalCenter: parent.verticalCenter
                verticalCenterOffset: 6
            }
            text: Qt.application.version
            color: "#6f82a8"
            font.pixelSize: 14
        }
    }
}
