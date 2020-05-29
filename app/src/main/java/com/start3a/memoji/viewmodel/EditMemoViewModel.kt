package com.start3a.memoji.viewmodel

import android.content.Context
import android.net.Uri
import android.view.Menu
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.start3a.memoji.ImageManager
import com.start3a.memoji.MemoAlarmTool
import com.start3a.memoji.Model.MemoDao
import com.start3a.memoji.R
import com.start3a.memoji.data.MemoData
import com.start3a.memoji.data.MemoImageFilePath
import com.start3a.memoji.data.RealmImageFileLiveData
import io.realm.Realm
import io.realm.RealmList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

class EditMemoViewModel : ViewModel() {

    // 텍스트
    val title: MutableLiveData<String> = MutableLiveData<String>()
    val content: MutableLiveData<String> = MutableLiveData<String>()
    var titleTemp: String = ""
    var contentTemp: String = ""

    // 메모 텍스트 저장 리스너
    lateinit var memoTitleSaveListener: () -> Unit
    lateinit var memoContentSaveListener: () -> Unit

    // 이미지
    val imageFileLinks: RealmImageFileLiveData<MemoImageFilePath> by lazy {
        RealmImageFileLiveData(memoData.imageFileLinks)
    }
    lateinit var deleteImageListListener: () -> Unit

    // 메모 저장 시 이미지 파일 삭제 반영
    private val imageFileDeleteList: MutableList<MemoImageFilePath> by lazy {
        mutableListOf<MemoImageFilePath>()
    }

    // 알람
    // 현재 알람 리스트
    val alarmTimeList: MutableLiveData<RealmList<Date>> =
        MutableLiveData<RealmList<Date>>().apply { value = RealmList() }

    // 초기 등록 알람 리스트
    // 시스템에 등록된 알람들. 알람 갱신 비교용
    val alarmTimeListTemp: MutableList<Date> = mutableListOf()
    lateinit var datePickerShowListener: (index: Int) -> Unit
    lateinit var deleteAlarmListListener: () -> Unit

    // 코루틴
    private val MAX_COROUTINE_JOB = 4
    private var mJob = Job()
    private val backScope = CoroutineScope(Dispatchers.Default + mJob)

    // Context
    lateinit var context: Context

    // 임시 메모 정보
    // 새 메모 : null
    // 기존 메모 : !null
    var memoId: String? = null
    private var memoData = MemoData()

    // 데이터 삭제 인덱스 리스트
    // 화면 갱신 시 데이터 유지
    var deleteDataList: MutableList<Int> = mutableListOf()

    // UI 정보
    var mMenu: Menu? = null
    var editable: MutableLiveData<Boolean> = MutableLiveData<Boolean>()
    var fragBtnClicked: MutableLiveData<Int> =
        MutableLiveData<Int>().apply { value = R.id.action_fragment_text }

    // DB
    private val mRealm: Realm by lazy {
        Realm.getDefaultInstance()
    }
    private val mMemoDao: MemoDao by lazy {
        MemoDao(mRealm)
    }

    override fun onCleared() {
        super.onCleared()
        mJob.cancel()
        mRealm.close()
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

    fun isExistText(): Boolean {
        return titleTemp.isNotEmpty() || contentTemp.isNotEmpty()
    }

    fun isExistImage(): Boolean {
        return imageFileLinks.value?.size ?: 0 > 0
    }

    // 메모 불러오기
    fun Load_MemoData(id: String) {
        memoData = mMemoDao.selectMemo(id)
        memoId = id
        titleTemp = memoData.title
        contentTemp = memoData.content
        alarmTimeList.value = memoData.alarmTimeList
        alarmTimeListTemp.addAll(memoData.alarmTimeList)
    }

    // 메모 수정
    suspend fun AddOrUpdate_MemoData() {
        imageFileLinks.value?.let { images ->
            // 내용이 존재할 경우
            if (titleTemp.isNotEmpty() || contentTemp.isNotEmpty() || images.size > 0) {
                val alarmTimeListValue = alarmTimeList.value!!

                // 새 메모일 경우
                // 해당 메모 이미지 디렉토리 생성
                if (memoId.isNullOrEmpty()) {
                    ImageManager.SetImageDirectory(context.filesDir, memoData.id)
                }

                // 파일이 있는 이미지 삭제 리스트
                backScope.launch {
                    for (item in imageFileDeleteList) {
                        File(item.thumbnailPath).delete()
                        File(item.originalPath).delete()
                    }

                    var num = 0
                    var max = images.size.run {
                        if (this < MAX_COROUTINE_JOB)
                            this
                        else MAX_COROUTINE_JOB
                    }
                    var index = images.size - 1
                    while (num++ < max) {
                        launch {
                            // 거꾸로 이미지 리스트 탐색
                            while (index >= 0) {
                                // 파일이 존재하지 않는 아이템만 탐색 / 파일 저장
                                if (index-- >= 0 && images[index + 1]!!.thumbnailPath.isEmpty()) {
                                    val item = images[index + 1]!!
                                    val fileLinks = GetMemoImageFilePath(Uri.parse(item.uri))
                                    item.thumbnailPath = fileLinks.thumbnailPath
                                    item.originalPath = fileLinks.originalPath
                                } else break
                            }
                        }
                    }
                }.join()

                // 기존 알람 삭제
                for (alarmTime in alarmTimeListTemp) {
                    // 이전 시간 알람 OR 해당 시간 알람이 제거됨
                    if (alarmTime.before(Date()) || !alarmTimeListValue.contains(alarmTime))
                        MemoAlarmTool.deleteAlarm(context, memoData.id, alarmTime)
                }
                // 새로 알람 갱신
                val deleteIndexList = mutableListOf<Int>()
                for (i in 0 until alarmTimeListValue.size) {
                    val time = alarmTimeListValue[i]!!
                    // 현재 이후 알람 AND 신규 생성 알람
                    if (time.after(Date())) {
                        if (!alarmTimeListTemp.contains(time)) {
                            MemoAlarmTool.addAlarm(context, memoData.id, time)
                        }
                    }
                    // 시간이 지난 알람은 제거 리스트에 추가됨
                    // 나중에 삭제하는 이유 : ConcurrentModificationException
                    // 어떤 스레드가 Iterator가 반복중인 Collection을 수정하려 할 때 발생
                    else {
                        deleteIndexList.add(i)
                    }
                }
                // 지난 알람 삭제
                for (i in 0 until deleteIndexList.size)
                    alarmTimeListValue.removeAt(i)
                //  DB에 저장
                mMemoDao.addUpdateMemo(
                    memoData, titleTemp, contentTemp, images, alarmTimeListValue
                )
            }
            // 내용이 없는 기존 메모이면 DB + 디렉토리 삭제
            else Delete_MemoData()
        }
    }

    private fun GetMemoImageFilePath(uri: Uri): MemoImageFilePath {
        val bitmapOriginal = ImageManager.GetURI_To_Bitmap(context, uri)
        val bitmapThumbnail = ImageManager.GetURI_To_BitmapResize(context, uri)
        // 비트맵이 생성됨
        if (bitmapOriginal != null && bitmapThumbnail != null) {
            val pathOriginal = ImageManager.SaveBitmapToJpeg(
                bitmapOriginal,
                context.filesDir,
                "/" + memoId + ImageManager.ORIGINAL_PATH
            )
            val pathThumbnail = ImageManager.SaveBitmapToJpeg(
                bitmapThumbnail,
                context.filesDir,
                "/" + memoId + ImageManager.THUMBNAIL_PATH
            )

            bitmapOriginal.recycle()
            bitmapThumbnail.recycle()
            // 파일이 생성됨
            if (pathOriginal.isNotEmpty() && pathThumbnail.isNotEmpty()) {
                return MemoImageFilePath(thumbnailPath = pathThumbnail, originalPath = pathOriginal)
            }
            // 적어도 둘 중 하나의 파일이 생성되지 않음
            else {
                // 존재하는 파일은 제거
                File(pathOriginal).delete()
                File(pathThumbnail).delete()
            }
        }
        return MemoImageFilePath(
            thumbnailPath = ImageManager.UNSAVABLE_IMAGE,
            originalPath = ImageManager.UNSAVABLE_IMAGE
        )
    }

    // 메모 삭제
    fun Delete_MemoData() {
        // 기존 메모
        if (!memoId.isNullOrEmpty()) {
            // 설정된 알람 삭제
            // alarmTimeListTemp : 메모 초기 알람 상태 보유
            // 아직 저장하지 않았으므로 초기 알람만 설정되어 있음
            for (alarmTime in alarmTimeListTemp)
                MemoAlarmTool.deleteAlarm(context, memoData.id, alarmTime)
            // 디렉토리 제거
            setDirEmpty(context.filesDir.toString() + "/" + memoId)
            // DB에서 제거
            mMemoDao.deleteMemo(memoData.id)
        }
    }

    // 이미지 저장 디렉토리 삭제 용도
    // 디렉토리 내부 파일 제거 후 해당 디렉토리 제거
    // 내부 파일이 비워져야 해당 디렉토리가 제거됨
    private fun setDirEmpty(dirName: String) {
        val dir = File(dirName)
        val childFileList = dir.listFiles()

        if (dir.exists()) {
            for (childFile in childFileList) {
                if (childFile.isDirectory) {
                    // 자식 디렉토리로 재귀 호출
                    setDirEmpty(childFile.absolutePath)
                } else {
                    childFile.delete()
                }
            }
            dir.delete()
        }
    }

    // 이미지 추가
    fun AddImageList(listAdd: List<Uri>) {
        // uri 형태로 출력
        val list = RealmList<MemoImageFilePath>()
        for (element in listAdd)
            list.add(MemoImageFilePath(element.toString()))
        imageFileLinks.AddDatas(list)
    }

    // 이미지 삭제
    fun DeleteImageList(list: List<Int>) {
        // 내림차순 정렬 후 인덱스 별 삭제
        Collections.sort(list, Collections.reverseOrder())

        imageFileLinks.value?.let { files ->
            for (i in list) {
                // 파일이 존재하는 이미지
                if (files[i]!!.thumbnailPath.isNotEmpty() &&
                    files[i]!!.thumbnailPath != ImageManager.UNSAVABLE_IMAGE
                ) {
                    // 파일 삭제 리스트에 추가
                    // 나중에 메모 갱신 시 사용됨
                    imageFileDeleteList.add(files[i]!!)
                }
                files.removeAt(i)
            }
        }
    }

    fun SetAlarm(time: Date): Boolean {
        // 동일 알람이 존재하지 않아야 생성됨
        val isSetable = alarmTimeList.value!!.none { it == time }
        if (isSetable) {
            val list = alarmTimeList?.value!!
            list.add(time)
            list.sort()
            alarmTimeList.value = list
        }
        return isSetable
    }

    fun UpdateAlarm(index: Int, time: Date): Boolean {
        // 동일 알람이 존재하지 않아야 수정됨
        val isUpdatable = alarmTimeList.value!!.none { it == time }
        if (isUpdatable) {
            alarmTimeList.value!![index] = time
            alarmTimeList.value!!.sort()
        }
        return isUpdatable
    }

    fun DeleteAlarm(list: List<Int>) {
        // 내림차순 정렬
        Collections.sort(list, Collections.reverseOrder())
        val deleteList = alarmTimeList.value!!
        for (i in list) {
            deleteList.removeAt(i)
        }
        alarmTimeList.value = deleteList
    }
}