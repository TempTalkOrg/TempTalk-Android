/* *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair


/**
 * Utility class for working with identity keys.
 *
 * @author Moxie Marlinspike
 */
object IdentityKeyUtil {
    fun generateIdentityKeyPair(): IdentityKeyPair {
        val djbKeyPair = ECKeyPair.generate()
        val djbIdentityKey = IdentityKey(djbKeyPair.publicKey)
        val djbPrivateKey = djbKeyPair.privateKey
        return IdentityKeyPair(djbIdentityKey, djbPrivateKey)
    }
}