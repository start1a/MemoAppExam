package com.start3a.memoji.Model

import com.start3a.memoji.data.MemoData
import com.start3a.memoji.data.MemoImageFilePath
import io.realm.Realm
import io.realm.RealmList
import io.realm.RealmResults
import io.realm.Sort
import java.util.*

// DB에서 쿼리할 함수 모음
class MemoDao(private val realm: Realm) {

    fun getAllMemos(): RealmResults<MemoData> {
        return realm.where(MemoData::class.java)
            .sort("date", Sort.DESCENDING)
            .findAll()
    }

    fun selectMemo(id: String): MemoData {
        val data = realm.where(MemoData::class.java)
            .equalTo("id", id)
            .findFirst() as MemoData
        return realm.copyFromRealm(data)
    }

    fun addUpdateMemo(
        memoData: MemoData,
        title: String,
        content: String,
        imageFileLinks: RealmList<MemoImageFilePath>,
        alarmTimeList: RealmList<Date>
    ) {
        realm.executeTransaction {
            memoData.title = title
            memoData.content = content
            memoData.date = Date()
            memoData.alarmTimeList = alarmTimeList

            if (content.length > 100)
                memoData.summary = content.substring(0..100) + ".."
            else
                memoData.summary = content

            if (!memoData.isManaged) {
                memoData.imageFileLinks = imageFileLinks
                it.copyToRealmOrUpdate(memoData)
            }
        }
    }

    fun getActiveAlarm(): RealmResults<MemoData> {
        return realm.where(MemoData::class.java)
            .greaterThan("alarmTimeList", Date())
            .findAll()
    }

    fun deleteMemo(id: String) {
        realm.executeTransaction {
            it.where(MemoData::class.java)
                .equalTo("id", id)
                .findFirst()
                ?.deleteFromRealm()
        }
    }

    fun deleteAlarmMemo(id: String, alarmTime: Date) {
        realm.executeTransaction {
            val memoData = it.where(MemoData::class.java)
                .equalTo("id", id)
                .findFirst()
            for (time in memoData!!.alarmTimeList) {
                if (time == alarmTime) {
                    memoData.alarmTimeList.remove(time)
                    break
                }
            }
        }
    }
}