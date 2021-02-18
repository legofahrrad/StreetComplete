package de.westnordost.streetcomplete.data.download;

import java.util.Arrays;
import java.util.List;

import dagger.Module;
import dagger.Provides;
import de.westnordost.streetcomplete.data.maptiles.MapTilesDownloader;
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataDownloader;
import de.westnordost.streetcomplete.data.osmnotes.NotesDownloader;

@Module
public class DownloadModule2
{
	/* NOTE: For some reason, when converting this to Kotlin, Dagger 2 does not find this anymore
	*  and cannot provide the dependency for UploadService. So, it must stay in Java (for now) */
	@Provides public static List<? extends Downloader> downloaders(
			NotesDownloader notesDownloader,
			MapDataDownloader mapDataDownloader,
			MapTilesDownloader mapTilesDownloader
	) {
		return Arrays.asList(notesDownloader, mapDataDownloader, mapTilesDownloader);
	}
}
