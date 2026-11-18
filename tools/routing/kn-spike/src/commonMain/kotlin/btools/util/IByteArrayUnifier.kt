// Port of btools.util.IByteArrayUnifier (BRouter 1.7.11-beta, MIT)
package btools.util

interface IByteArrayUnifier {
  fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray
}
