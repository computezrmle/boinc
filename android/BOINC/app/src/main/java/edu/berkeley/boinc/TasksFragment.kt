/*
 * This file is part of BOINC.
 * http://boinc.berkeley.edu
 * Copyright (C) 2020 University of California
 *
 * BOINC is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * BOINC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with BOINC.  If not, see <http://www.gnu.org/licenses/>.
 */
package edu.berkeley.boinc

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import edu.berkeley.boinc.adapter.TasksListAdapter
import edu.berkeley.boinc.rpc.Result
import edu.berkeley.boinc.rpc.RpcClient
import edu.berkeley.boinc.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class TasksFragment : Fragment() {
    private lateinit var listAdapter: TasksListAdapter
    private val data: MutableList<TaskData> = ArrayList()
    private val mClientStatusChangeRec: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Logging.VERBOSE) {
                Log.d(Logging.TAG, "TasksActivity onReceive")
            }
            loadData()
        }
    }
    private val ifcsc = IntentFilter("edu.berkeley.boinc.clientstatuschange")
    private val itemClickListener = OnItemClickListener { _: AdapterView<*>?, _: View?, position: Int, _: Long ->
        val task = listAdapter.getItem(position)
        task!!.expanded = !task.expanded
        listAdapter.notifyDataSetChanged()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (Logging.DEBUG) {
            Log.d(Logging.TAG, "TasksFragment onCreateView")
        }
        // Inflate the layout for this fragment
        val layout = inflater.inflate(R.layout.tasks_layout, container, false)
        val lv = layout.findViewById<ListView>(R.id.tasksList)
        listAdapter = TasksListAdapter(activity, R.id.tasksList, data)
        lv.adapter = listAdapter
        lv.onItemClickListener = itemClickListener
        return layout
    }

    override fun onResume() {
        super.onResume()
        //register noisy clientStatusChangeReceiver here, so only active when Activity is visible
        if (Logging.DEBUG) {
            Log.d(Logging.TAG, "TasksFragment register receiver")
        }
        requireActivity().registerReceiver(mClientStatusChangeRec, ifcsc)
        loadData()
    }

    override fun onPause() {
        //unregister receiver, so there are not multiple intents flying in
        if (Logging.DEBUG) {
            Log.d(Logging.TAG, "TasksFragment remove receiver")
        }
        requireActivity().unregisterReceiver(mClientStatusChangeRec)
        super.onPause()
    }

    private fun loadData() {
        // try to get current client status from monitor
        val tmpA = try {
            BOINCActivity.monitor!!.tasks
        } catch (e: Exception) {
            if (Logging.WARNING) {
                Log.w(Logging.TAG, "TasksActivity: Could not load data, clientStatus not initialized.")
            }
            return
        }
        //setup list and adapter
        if (tmpA != null) { //can be null before first monitor status cycle (e.g. when not logged in or during startup)
            //deep copy, so ArrayList adapter actually recognizes the difference
            updateData(tmpA)
            listAdapter.notifyDataSetChanged() //force list adapter to refresh
        } else {
            if (Logging.WARNING) {
                Log.w(Logging.TAG, "loadData: array is null, rpc failed")
            }
        }
    }

    private fun updateData(newData: List<Result>) {
        //loop through all received Result items to add new results
        for (rpcResult in newData) {
            //check whether this Result is new
            val index = data.indexOfFirst { it.id == rpcResult.name }
            if (index == -1) { // result is new, add
                if (Logging.DEBUG) {
                    Log.d(Logging.TAG, "new result found, id: " + rpcResult.name)
                }
                data.add(TaskData(rpcResult))
            } else { // result was present before, update its data
                data[index].updateResultData(rpcResult)
            }
        }

        //loop through the list adapter to find removed (ready/aborted) Results
        data.removeIf { item -> newData.none { it.name == item.id } }
    }

    inner class TaskData(var result: Result) {
        @JvmField
        var expanded = false
        @JvmField
        var id = result.name
        @JvmField
        var nextState = -1
        private var loopCounter = 0
        // amount of refresh, until transition times out
        private val transitionTimeout = resources.getInteger(R.integer.tasks_transistion_timeout_number_monitor_loops)

        fun updateResultData(result: Result) {
            this.result = result
            val currentState = determineState()
            if (nextState == -1) {
                return
            }
            if (currentState == nextState) {
                if (Logging.DEBUG) {
                    Log.d(Logging.TAG, "nextState met! $nextState")
                }
                nextState = -1
                loopCounter = 0
            } else {
                if (loopCounter < transitionTimeout) {
                    if (Logging.DEBUG) {
                        Log.d(Logging.TAG,
                                "nextState not met yet! " + nextState + " vs " + currentState + " loopCounter: " +
                                        loopCounter)
                    }
                    loopCounter++
                } else {
                    if (Logging.DEBUG) {
                        Log.d(Logging.TAG,
                                "transition timed out! " + nextState + " vs " + currentState + " loopCounter: " +
                                        loopCounter)
                    }
                    nextState = -1
                    loopCounter = 0
                }
            }
        }

        @JvmField
        val iconClickListener = View.OnClickListener { view: View ->
            try {
                when (val operation = view.tag as Int) {
                    RpcClient.RESULT_SUSPEND -> {
                        nextState = RESULT_SUSPENDED_VIA_GUI
                        lifecycleScope.launch {
                            performResultOperation(result.projectURL, result.name, operation)
                        }
                    }
                    RpcClient.RESULT_RESUME -> {
                        nextState = PROCESS_EXECUTING
                        lifecycleScope.launch {
                            performResultOperation(result.projectURL, result.name, operation)
                        }
                    }
                    RpcClient.RESULT_ABORT -> {
                        val dialog = Dialog(activity!!)
                        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                        dialog.setContentView(R.layout.dialog_confirm)
                        val confirm = dialog.findViewById<Button>(R.id.confirm)
                        val tvTitle = dialog.findViewById<TextView>(R.id.title)
                        val tvMessage = dialog.findViewById<TextView>(R.id.message)
                        tvTitle.setText(R.string.confirm_abort_task_title)
                        tvMessage.text = getString(R.string.confirm_abort_task_message, result.name)
                        confirm.setText(R.string.confirm_abort_task_confirm)
                        confirm.setOnClickListener {
                            nextState = RESULT_ABORTED
                            lifecycleScope.launch {
                                performResultOperation(result.projectURL, result.name, operation)
                            }
                            dialog.dismiss()
                        }
                        val cancel = dialog.findViewById<Button>(R.id.cancel)
                        cancel.setOnClickListener { dialog.dismiss() }
                        dialog.show()
                    }
                    else -> if (Logging.WARNING) {
                        Log.w(Logging.TAG, "could not map operation tag")
                    }
                }
                listAdapter.notifyDataSetChanged() //force list adapter to refresh
            } catch (e: Exception) {
                if (Logging.WARNING) {
                    Log.w(Logging.TAG, "failed parsing view tag")
                }
            }
        }

        fun determineState(): Int {
            if (result.isSuspendedViaGUI) {
                return RESULT_SUSPENDED_VIA_GUI
            }
            if (result.isProjectSuspendedViaGUI) {
                return RESULT_PROJECT_SUSPENDED
            }
            if (result.isReadyToReport && result.state != RESULT_ABORTED && result.state != RESULT_COMPUTE_ERROR) {
                return RESULT_READY_TO_REPORT
            }
            return if (result.isActiveTask) {
                result.activeTaskState
            } else {
                result.state
            }
        }

        val isTaskActive: Boolean
            get() = result.isActiveTask
    }

    private suspend fun performResultOperation(url: String, name: String, operation: Int) = coroutineScope {
        val success = withContext(Dispatchers.Default) {
            try {
                if (Logging.DEBUG) {
                    Log.d(Logging.TAG, "URL: $url, Name: $name, operation: $operation")
                }
                return@withContext BOINCActivity.monitor!!.resultOp(operation, url, name)
            } catch (e: Exception) {
                if (Logging.WARNING) {
                    Log.w(Logging.TAG, "performResultOperation() error: ", e)
                }
            }
            return@withContext false
        }

        if (success) {
            try {
                BOINCActivity.monitor!!.forceRefresh()
            } catch (e: RemoteException) {
                if (Logging.ERROR) {
                    Log.e(Logging.TAG, "performResultOperation() error: ", e)
                }
            }
        } else if (Logging.WARNING) {
            Log.w(Logging.TAG, "performResultOperation() failed.")
        }
    }
}
