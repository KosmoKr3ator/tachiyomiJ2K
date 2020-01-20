package eu.kanade.tachiyomi.ui.manga.info

import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import com.jakewharton.rxrelay.BehaviorRelay
import com.jakewharton.rxrelay.PublishRelay
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.DiskUtil
import eu.kanade.tachiyomi.util.ImageUtil
import eu.kanade.tachiyomi.util.isNullOrUnsubscribed
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.*

/**
 * Presenter of MangaInfoFragment.
 * Contains information and data for fragment.
 * Observable updates should be called from here.
 */
class MangaInfoPresenter(
        val manga: Manga,
        val source: Source,
        private val chapterCountRelay: BehaviorRelay<Float>,
        private val lastUpdateRelay: BehaviorRelay<Date>,
        private val mangaFavoriteRelay: PublishRelay<Boolean>,
        private val db: DatabaseHelper = Injekt.get(),
        private val downloadManager: DownloadManager = Injekt.get(),
        private val coverCache: CoverCache = Injekt.get()
) : BasePresenter<MangaInfoController>() {

    /**
     * Subscription to send the manga to the view.
     */
    private var viewMangaSubscription: Subscription? = null

    /**
     * Subscription to update the manga from the source.
     */
    private var fetchMangaSubscription: Subscription? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        sendMangaToView()

        // Update chapter count
        chapterCountRelay.observeOn(AndroidSchedulers.mainThread())
                .subscribeLatestCache(MangaInfoController::setChapterCount)

        // Update favorite status
        mangaFavoriteRelay.observeOn(AndroidSchedulers.mainThread())
                .subscribe { setFavorite(it) }
                .apply { add(this) }

        //update last update date
        lastUpdateRelay.observeOn(AndroidSchedulers.mainThread())
                .subscribeLatestCache(MangaInfoController::setLastUpdateDate)
    }

    /**
     * Sends the active manga to the view.
     */
    fun sendMangaToView() {
        viewMangaSubscription?.let { remove(it) }
        viewMangaSubscription = Observable.just(manga)
                .subscribeLatestCache({ view, manga -> view.onNextManga(manga, source) })
    }

    /**
     * Fetch manga information from source.
     */
    fun fetchMangaFromSource() {
        if (!fetchMangaSubscription.isNullOrUnsubscribed()) return
        fetchMangaSubscription = Observable.defer { source.fetchMangaDetails(manga) }
                .map { networkManga ->
                    manga.copyFrom(networkManga)
                    manga.initialized = true
                    db.insertManga(manga).executeAsBlocking()
                    coverCache.deleteFromCache(manga.thumbnail_url)
                    (manga as? MangaImpl)?.last_cover_fetch = Date().time
                    manga
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { sendMangaToView() }
                .subscribeFirst({ view, _ ->
                    view.onFetchMangaDone()
                }, MangaInfoController::onFetchMangaError)
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     *
     * @return the new status of the manga.
     */
    fun toggleFavorite(): Boolean {
        manga.favorite = !manga.favorite
        db.insertManga(manga).executeAsBlocking()
        sendMangaToView()
        return manga.favorite
    }

    fun confirmDeletion() {
        coverCache.deleteFromCache(manga.thumbnail_url)
        downloadManager.deleteManga(manga, source)
    }

    fun setFavorite(favorite: Boolean) {
        if (manga.favorite == favorite) {
            return
        }
        toggleFavorite()
    }

    fun shareManga(cover: Bitmap) {
        val context = Injekt.get<Application>()

        val destDir = File(context.cacheDir, "shared_image")

        Observable.fromCallable { destDir.deleteRecursively() } // Keep only the last shared file
            .map { saveImage(cover, destDir, manga) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeFirst(
                { view, file -> view.shareManga(file) },
                { view, error -> view.shareManga() }
            )
    }

    private fun saveImage(cover:Bitmap, directory: File, manga: Manga): File? {
        directory.mkdirs()

        // Build destination file.
        val filename = DiskUtil.buildValidFilename("${manga.trueTitle()} - Cover.jpg")

        val destFile = File(directory, filename)
        val stream: OutputStream = FileOutputStream(destFile)
        cover.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        stream.flush()
        stream.close()
        return destFile
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    fun getCategories(): List<Category> {
        return db.getCategories().executeAsBlocking()
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    fun getMangaCategoryIds(manga: Manga): Array<Int> {
        val categories = db.getCategoriesForManga(manga).executeAsBlocking()
        return categories.mapNotNull { it.id }.toTypedArray()
    }

    /**
     * Move the given manga to categories.
     *
     * @param manga the manga to move.
     * @param categories the selected categories.
     */
    fun moveMangaToCategories(manga: Manga, categories: List<Category>) {
        val mc = categories.filter { it.id != 0 }.map { MangaCategory.create(manga, it) }
        db.setMangaCategories(mc, listOf(manga))
    }

    /**
     * Move the given manga to the category.
     *
     * @param manga the manga to move.
     * @param category the selected category, or null for default category.
     */
    fun moveMangaToCategory(manga: Manga, category: Category?) {
        moveMangaToCategories(manga, listOfNotNull(category))
    }

}
