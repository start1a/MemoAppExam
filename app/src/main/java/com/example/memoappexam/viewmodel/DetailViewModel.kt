package com.example.memoappexam.viewmodel

import android.content.Context
import android.net.Uri
import android.view.Menu
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.memoappexam.ImageManager
import com.example.memoappexam.Model.MemoDao
import com.example.memoappexam.R
import com.example.memoappexam.data.MemoData
import com.example.memoappexam.data.MemoImageFilePath
import com.example.memoappexam.data.RealmImageFileLiveData
import io.realm.Realm
import io.realm.RealmList
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class DetailViewModel : ViewModel() {

    // 메모 정보
    // 텍스트
    val title: MutableLiveData<String> = MutableLiveData<String>()
    val content: MutableLiveData<String> = MutableLiveData<String>()

    // 이미지
    val imageFileLinks: RealmImageFileLiveData<MemoImageFilePath> by lazy {
        RealmImageFileLiveData(memoData.imageFileLinks)
    }

    // 코루틴
    private val MAX_COROUTINE_JOB = 4
    private var mJob = Job()
    private val backScope = CoroutineScope(Dispatchers.Default + mJob)

    // Context
    lateinit var context: Context

    // 임시 메모 정보
    var memoId: String? = null
    private var memoData = MemoData()

    // 텍스트 임시 데이터
    var titleTemp: String = ""
    var contentTemp: String = ""

    // 이미지 삭제 리스트
    var deleteImageList: MutableList<Int> = mutableListOf()
    lateinit var deleteImageListListener: () -> Unit

    // UI 정보
    var mMenu: Menu? = null
    var editable: MutableLiveData<Boolean> = MutableLiveData<Boolean>()
    var fragBtnClicked: MutableLiveData<Int> =
        MutableLiveData<Int>().apply { value = R.id.action_fragment_text }

    // 메모 텍스트 저장 리스너
    lateinit var memoTitleSaveListener: () -> Unit
    lateinit var memoContentSaveListener: () -> Unit

    // UI 스레드
    private val uiRealm: Realm by lazy {
        Realm.getDefaultInstance()
    }
    private val uiMemoDao: MemoDao by lazy {
        MemoDao(uiRealm)
    }

    override fun onCleared() {
        super.onCleared()
        mJob.cancel()
        uiRealm.close()
    }

    fun saveText() {
        title.value = titleTemp
        content.value = contentTemp
    }

    fun setEditable(on: Boolean) {
        editable.value = on
    }

    fun setFragBtn(type: Int) {
        fragBtnClicked.value = type
    }

    // 메모 불러오기
    fun Load_MemoData(id: String) {
        memoData = uiMemoDao.selectMemo(id)
        memoId = id
        titleTemp = memoData.title
        contentTemp = memoData.content
    }

    // 메모 수정
    suspend fun Update_MemoData() {
        imageFileLinks.value!!.let { images ->

            // 내용이 존재할 경우
            if (titleTemp.length > 0 || contentTemp.length > 0 || images.size > 0) {
                // 비동기 처리 : 이미지 저장
                backScope.launch {
                    // 새 메모일 경우
                    // 해당 메모 이미지 디렉토리 생성
                    launch {
                        if (memoId.isNullOrEmpty())
                            ImageManager.SetImageDirectory(context.filesDir, memoData.id)
                    }.join()

                    var num = 0
                    var index = images.size - 1
                    while (num++ < MAX_COROUTINE_JOB) {
                        launch {
                            // 거꾸로 이미지 리스트 탐색
                            while (index >= 0) {
                                yield()
                                // 파일이 존재하지 않는 아이템만 탐색 / 파일 저장
                                if (index >= 0 && (images[index]!!.thumbnailPath.isEmpty() || images[index]!!.originalPath.isEmpty())) {
                                    SaveImageFile(index--)
                                } else break
                            }
                        }
                    }
                }.join()

                uiMemoDao.addUpdateMemo(
                    memoData, titleTemp, contentTemp, images
                )
            }
            // 내용이 없는 기존 메모이면 DB + 디렉토리 삭제
            else Delete_MemoData()
        }
    }

    // 이미지 파일 생성 후 DB에 데이터 path 저장
    fun SaveImageFile(index: Int) {
        // originalPath == thumbnailPath (동일 데이터)
        imageFileLinks.value!!.let { images ->
            val item =
                GetMemoImageFilePath(Uri.parse(images[index]?.uri))
            // 파일 저장 성공 시
            if (item != null) {
                images[index]?.thumbnailPath = item.thumbnailPath
                images[index]?.originalPath = item.originalPath
            }
        }
    }

    // 메모 삭제
    fun Delete_MemoData() {
        // 기존 메모
        if (!memoId.isNullOrEmpty()) {
            // 메모 디렉토리 삭제
            val memoPath = context.filesDir.toString() + memoData.id
            val deleteFile = File(memoPath)
            if (deleteFile.exists()) deleteFile.delete()
            // DB에서 제거
            uiMemoDao.deleteMemo(memoData.id)
        }
    }

    // 이미지 추가
    fun add_ImageMemoDataList(listAdd: List<Uri>) {
        // uri 형태로 출력
        val list = RealmList<MemoImageFilePath>()
        for (element in listAdd)
            list.add(MemoImageFilePath(element.toString()))
        imageFileLinks.AddDatas(list)
    }

    // uri -> bitmap -> File 전환
    // 저장 성공 : FilePath가 저장된 객체 반환
    // null 반환
    fun GetMemoImageFilePath(uri: Uri): MemoImageFilePath? {
        val bitmapOriginal = ImageManager.GetURI_To_Bitmap(context, uri)
        val bitmapThumbnail = ImageManager.GetURI_To_BitmapResize(context, uri)
        // 비트맵이 생성됨
        if (bitmapOriginal != null && bitmapThumbnail != null) {
            val pathOriginal = ImageManager.SaveBitmapToJpeg(
                bitmapOriginal,
                context.filesDir,
                "/" + memoData.id + ImageManager.ORIGINAL_PATH
            )
            val pathThumbnail = ImageManager.SaveBitmapToJpeg(
                bitmapThumbnail,
                context.filesDir,
                "/" + memoData.id + ImageManager.THUMBNAIL_PATH
            )

            bitmapOriginal.recycle()
            bitmapThumbnail.recycle()
            // 파일이 생성됨
            if (pathOriginal.isNotEmpty() && pathThumbnail.isNotEmpty()) {
                return MemoImageFilePath("", pathThumbnail, pathOriginal)
            }
        }
        return null
    }

    // 이미지 삭제
    fun Delete_ImageMemoDataList(list: List<Int>) {
        // 내림차순 정렬 후 인덱스 별 삭제
        Collections.sort(list, Collections.reverseOrder())

        imageFileLinks.value?.let { files ->
            for (index in list) {
                // 이미지 파일이 존재하면 삭제
                File(files[index]?.thumbnailPath).delete()
                File(files[index]?.originalPath).delete()
                files.removeAt(index)
            }
        }
    }
}