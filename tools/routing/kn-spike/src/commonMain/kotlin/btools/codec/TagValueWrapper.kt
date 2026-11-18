// Port of btools.codec.TagValueWrapper (BRouter 1.7.11-beta, MIT)
package btools.codec

/**
 * TagValueWrapper wrapps a description bitmap
 * to add the access-type
 */
class TagValueWrapper {
  var data: ByteArray = btools.util.ByteDataReader.EMPTY // always assigned by the decoder
  var accessType: Int = 0
}
