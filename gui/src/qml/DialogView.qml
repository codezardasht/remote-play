import QtQuick
import QtQuick.Layouts
import QtQuick.Controls
import QtQuick.Controls.Material

Item {
    id: dialog
    property alias header: headerLabel.text
    property alias title: titleLabel.text
    property alias buttonText: okButton.text
    property alias buttonEnabled: okButton.enabled
    property alias buttonVisible: okButton.visible
    property Item restoreFocusItem
    default property Item mainItem: null

    signal accepted()
    signal rejected()

    function close() {
        root.closeDialog();
    }

    Keys.onEscapePressed: close()

    Keys.onMenuPressed: {
        if (okButton.enabled)
            okButton.clicked()
    }

    StackView.onDeactivating: {
        restoreFocusItem = Window.window.activeFocusItem;
    }

    StackView.onActivated: {
        if (!restoreFocusItem) {
            let item = mainItem.nextItemInFocusChain();
            if (item)
                item.forceActiveFocus(Qt.TabFocusReason);
        } else {
            restoreFocusItem.forceActiveFocus(Qt.TabFocusReason);
            restoreFocusItem = null;
        }
    }

    onMainItemChanged: {
        if (mainItem) {
            mainItem.parent = contentItem;
            mainItem.anchors.fill = contentItem;
        }
    }

    // translucent sheet so the animated backdrop glows through softly
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#d90a1a3d" }
            GradientStop { position: 1.0; color: "#f0040c20" }
        }
    }

    Item {
        id: toolBar
        anchors {
            top: parent.top
            left: parent.left
            right: parent.right
        }
        height: 88

        PSIconButton {
            id: backButton
            anchors {
                left: parent.left
                leftMargin: 28
                verticalCenter: parent.verticalCenter
            }
            iconText: "❮"
            tip: qsTr("Back")
            glyph: root.controllerButton("moon")
            onClicked: {
                dialog.rejected();
                dialog.close();
            }
        }

        Text {
            id: titleLabel
            anchors {
                left: backButton.right
                leftMargin: 26
                verticalCenter: parent.verticalCenter
            }
            color: "#ffffff"
            font.pixelSize: 30
            font.weight: Font.Light
        }

        Text {
            id: headerLabel
            anchors {
                left: titleLabel.right
                right: okButton.visible ? okButton.left : parent.right
                leftMargin: 24
                rightMargin: 24
                verticalCenter: parent.verticalCenter
            }
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            wrapMode: Text.WordWrap
            maximumLineCount: 2
            elide: Text.ElideRight
            color: "#9fb4d8"
            font.pixelSize: 14
        }

        PSButton {
            id: okButton
            anchors {
                right: parent.right
                rightMargin: 28
                verticalCenter: parent.verticalCenter
            }
            primary: true
            implicitHeight: 54
            font.pixelSize: 20
            focusPolicy: Qt.NoFocus
            onClicked: dialog.accepted()
            icon.source: "qrc:/icons/options.svg"
            icon.width: 30
            icon.height: 30
        }

        Rectangle {
            anchors {
                left: parent.left
                right: parent.right
                bottom: parent.bottom
                leftMargin: 28
                rightMargin: 28
            }
            height: 1
            color: Qt.rgba(1, 1, 1, 0.10)
        }
    }

    Item {
        id: contentItem
        anchors {
            top: toolBar.bottom
            left: parent.left
            right: parent.right
            bottom: parent.bottom
        }
    }
}
