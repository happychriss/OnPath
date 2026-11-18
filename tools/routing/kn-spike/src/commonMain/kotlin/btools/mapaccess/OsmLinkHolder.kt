// Port of btools.mapaccess.OsmLinkHolder (BRouter 1.7.11-beta, MIT)
package btools.mapaccess

/**
 * Container for routig configs
 */
interface OsmLinkHolder {
  fun setNextForLink(holder: OsmLinkHolder?)

  fun getNextForLink(): OsmLinkHolder?
}
