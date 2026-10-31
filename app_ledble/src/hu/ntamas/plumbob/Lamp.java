package hu.ntamas.plumbob;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import java.util.ArrayDeque;

/** One lamp: its BLE handles, its write queue and the state the app keeps for its wearer. */
final class Lamp {
    final BluetoothDevice device;
    final String name;      // advertised name, e.g. LEDBLE-0A85E3
    final int family;       // Proto.BLE / BLE_SHORT / SMART, decides the frame layout
    String person;          // wearer from MainActivity.PEOPLE, null while unnamed

    BluetoothGatt gatt;
    BluetoothGattCharacteristic command;   // FFE1, non-null only while connected and usable
    final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    byte[] inFlight;

    int[] colour = {0, 0, 0};   // last colour sent; fades start from here
    int fadeId;                 // bumping it cancels a running fade
    int hp = 100;               // life points of the wearer

    Lamp(BluetoothDevice device, String name, String person) {
        this.device = device;
        this.name = name;
        this.family = Proto.family(name);
        this.person = person;
    }

    String mac() { return device.getAddress(); }

    boolean connected() { return command != null; }

    String label() { return person == null ? name : person + "  (" + name + ")"; }

    String shortName() { return person == null ? name : person; }
}
