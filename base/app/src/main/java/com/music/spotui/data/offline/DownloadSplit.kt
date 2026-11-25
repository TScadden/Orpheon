package com.music.spotui.data.offline

/**
 * How a list's rows split for a download: the songs that can be downloaded, and the
 * rows that are already files on this phone. A phone file has nothing to download —
 * and must never enter the offline store, whose remove() deletes the file it points
 * at — so on a mixed list the download speaks of the split ("N songs to download ·
 * M are already files on this phone") instead of implying every row came down.
 */
data class DownloadSplit(val downloadable: Int, val localFiles: Int) {
    companion object {
        /**
         * [rows] as (play address, track id). A row with a "local:" address and no
         * track id is a phone file; the downloadable count is the distinct non-blank
         * track ids — the same count the page's download button works with.
         */
        fun of(rows: List<Pair<String, String>>): DownloadSplit {
            var local = 0
            val ids = HashSet<String>()
            for ((url, id) in rows) {
                if (url.startsWith("local:") && id.isBlank()) local++
                else if (id.isNotBlank()) ids += id
            }
            return DownloadSplit(ids.size, local)
        }
    }
}
