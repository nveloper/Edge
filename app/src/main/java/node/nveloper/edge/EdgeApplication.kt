package node.nveloper.edge

import android.app.Application
import node.nveloper.edge.service.ServerKeeper

class EdgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Before anything can restart the server, so a crash loop is caught first.
        ServerKeeper.scanExits(this)
    }
}
