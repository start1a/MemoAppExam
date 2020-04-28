package com.example.memoappexam.views


import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.example.memoappexam.ImageListAdapter
import com.example.memoappexam.R
import com.example.memoappexam.viewmodel.DetailViewModel
import kotlinx.android.synthetic.main.fragment_memo_image_list.*

/**
 * A simple [Fragment] subclass.
 */
class MemoImageFragment : Fragment() {

    private lateinit var listImageAdapter: ImageListAdapter
    private var viewModel: DetailViewModel? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_memo_image_list, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        viewModel = activity!!.application!!.let {
            ViewModelProvider(
                activity!!.viewModelStore,
                ViewModelProvider.AndroidViewModelFactory(it)
            )
                .get(DetailViewModel::class.java)
        }

        viewModel!!.let { VM ->
            // 이미지 리스트 초기화
            VM.imageFileLinks.value?.let { paths ->
                listImageAdapter = ImageListAdapter(paths)
                listImageAdapter.deleteImageList = this.viewModel!!.deleteImageList
                imgListView.layoutManager = GridLayoutManager(activity, 3)
                imgListView.adapter = listImageAdapter
            }
            // 이미지 리스트 갱신
            VM.imageFileLinks.observe(this, Observer { files ->
                listImageAdapter.notifyDataSetChanged()
                if (files.size != 0) noImageView.visibility = View.GONE
                else noImageView.visibility = View.VISIBLE
            })

            // T: 수정모드, F: 보기모드 UI 갱신
            VM.editable.observe(this, Observer { editable ->
                listImageAdapter.let {
                    if (!editable) it.deleteImageList.clear()
                    it.editable = editable
                    // 삭제할 아이템의 체크박스 visible / gone
                    it.notifyDataSetChanged()
                }
            })
            // 이미지 클릭 리스너 : 보기 모드
            listImageAdapter.let { adapter ->
                adapter.itemClickListener = { path ->
                    val intent = Intent(activity, ImageViewActivity::class.java)
                    intent.putExtra("image", path)
                    startActivity(intent)
                }
            }
            // 선택된 이미지 삭제 리스너
            VM.deleteImageListListener = {
                // 데이터 삭제
                VM.Delete_ImageMemoDataList(listImageAdapter.deleteImageList)
                // 삭제 후 체크박스 해제
                listImageAdapter.let {
                    it.deleteImageList.clear()
                    it.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel!!.deleteImageList = listImageAdapter.deleteImageList
    }
}