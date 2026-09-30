package com.tyust.course.academic.plugin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.academic.plugin.runtime.PluginSandboxClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the JS bridge and cross-invocation course cache without enrolling anywhere. */
@RunWith(AndroidJUnit4::class)
class PluginCourseStateDeviceTest {
    @Test fun largePaginatedCourseListsKeepTheirOriginalTeachingClassParameters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val session = AcademicSessionStore().session("large-course-school", "synthetic-account", "https://school.example/")
        val manifest = PluginManifest(JSONObject().put("id", "test.large-course-list").put("version", "1.0.0")
            .put("kind", "independent").put("apiVersion", 3).put("capabilities", JSONArray()).put("network", JSONArray()))
        val source = """
            globalThis.plugin={selection:{
              courses:async(a,c,s)=>{
                const saved=await s.state.get('protocol:courses')||{};
                for(let i=0;i<600;i++){
                  const id=a.page+'-'+i;
                  saved[id]={id,sectionId:'section-'+id,name:'课程教学班参数'.repeat(40),
                    submit:{courseId:id,teachingClassId:'section-'+id}};
                }
                await s.state.set('protocol:courses',saved);
                return {ok:true,data:{count:Object.keys(saved).length}};
              },
              sections:async(a,c,s)=>{
                const saved=await s.state.get('protocol:courses');
                return {ok:true,data:saved[a.courseId]};
              }
            }};
        """.trimIndent()
        suspend fun invoke(method: String, args: JSONObject): JSONObject {
            val operation = PluginOperation(session, manifest, method)
            return try {
                PluginSandboxClient(context).execute(source, args, operation, PluginHost(operation, context.cacheDir)).getJSONObject("data")
            } finally { operation.close() }
        }
        try {
            assertEquals(600, invoke("selection.courses", JSONObject().put("page", 1)).getInt("count"))
            assertEquals(1200, invoke("selection.courses", JSONObject().put("page", 2)).getInt("count"))
            for (id in listOf("1-0", "1-599", "2-599")) {
                val course = invoke("selection.sections", JSONObject().put("courseId", id))
                assertEquals("section-$id", course.getString("sectionId"))
                assertEquals(id, course.getJSONObject("submit").getString("courseId"))
                assertEquals("section-$id", course.getJSONObject("submit").getString("teachingClassId"))
            }
        } finally { PluginHost.clearTemporaryState(session); session.retire() }
    }
}
