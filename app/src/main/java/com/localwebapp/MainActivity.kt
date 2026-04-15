port android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class RecentProject(val name: String, val uri: String, val lastOpened: Long)

class RecentProjectsAdapter(
    private var projects: MutableList<RecentProject>,
    private val onOpen: (RecentProject) -> Unit,
    private val onDelete: (RecentProject) -> Unit
) : RecyclerView.Adapter<RecentProjectsAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.projectName)
        val date: TextView = view.findViewById(R.id.projectDate)
        val deleteBtn: View = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val project = projects[position]
        holder.name.text = project.name
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        holder.date.text = "Last opened: ${sdf.format(Date(project.lastOpened))}"
        holder.itemView.setOnClickListener { onOpen(project) }
        holder.deleteBtn.setOnClickListener { onDelete(project) }
    }

    override fun getItemCount() = projects.size

    fun updateProjects(newProjects: List<RecentProject>) {
        projects.clear()
        projects.addAll(newProjects)
        notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecentProjectsAdapter
    private lateinit var emptyView: View
    private val prefs by lazy { getSharedPreferences("LocalWebApp", MODE_PRIVATE) }

    private val openFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { treeUri ->
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(treeUri, flags)
                val docFile = DocumentFile.fromTreeUri(this, treeUri)
                if (docFile == null || !docFile.isDirectory) {
                    Toast.makeText(this, "Invalid folder", Toast.LENGTH_SHORT).show()
                    return@let
                }
                val indexFile = docFile.findFile("index.html")
                if (indexFile == null) {
                    AlertDialog.Builder(this)
                        .setTitle("No index.html found")
                        .setMessage("Open anyway?")
                        .setPositiveButton("Open") { _, _ ->
                            launchWebApp(treeUri, docFile.name ?: "Web App")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    launchWebApp(treeUri, docFile.name ?: "Web App")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)

        adapter = RecentProjectsAdapter(
            mutableListOf(),
            onOpen = { launchWebApp(Uri.parse(it.uri), it.name) },
            onDelete = {
                AlertDialog.Builder(this)
                    .setTitle("Remove \"${it.name}\"?")
                    .setPositiveButton("Remove") { _, _ -> removeProject(it.uri) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            openFolderLauncher.launch(null)
        }
        loadProjects()
    }

    override fun onResume() { super.onResume(); loadProjects() }

    private fun launchWebApp(uri: Uri, name: String) {
        saveProject(name, uri.toString())
        startActivity(Intent(this, WebAppActivity::class.java).apply {
            putExtra(WebAppActivity.EXTRA_URI, uri.toString())
            putExtra(WebAppActivity.EXTRA_TITLE, name)
        })
    }

    private fun saveProject(name: String, uriStr: String) {
        val arr = loadJson()
        val filtered = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getString("uri") != uriStr }
            .toMutableList()
        filtered.add(0, JSONObject().apply {
            put("name", name); put("uri", uriStr)
            put("lastOpened", System.currentTimeMillis())
        })
        val out = JSONArray(); filtered.take(20).forEach { out.put(it) }
        prefs.edit().putString("projects", out.toString()).apply()
        loadProjects()
    }

    private fun removeProject(uriStr: String) {
        val arr = loadJson()
        val filtered = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getString("uri") != uriStr }
        val out = JSONArray(); filtered.forEach { out.put(it) }
        prefs.edit().putString("projects", out.toString()).apply()
        loadProjects()
    }

    private fun loadJson(): JSONArray {
        return try { JSONArray(prefs.getString("projects", "[]")) }
        catch (e: Exception) { JSONArray() }
    }

    private fun loadProjects() {
        val arr = loadJson()
        val list = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentProject(o.getString("name"), o.getString("uri"), o.getLong("lastOpened"))
        }
        adapter.updateProjects(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }
}
