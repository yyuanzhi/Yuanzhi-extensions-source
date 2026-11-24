package eu.kanade.tachiyomi.animeextension.pt.meowbls

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class MeowblsFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(Meowbls())
}
