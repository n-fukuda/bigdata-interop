package com.google.cloud.hadoop.io.bigquery.output;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.hadoop.fs.gcs.InMemoryGoogleHadoopFileSystem;
import com.google.cloud.hadoop.io.bigquery.BigQueryConfiguration;
import com.google.cloud.hadoop.io.bigquery.BigQueryFileFormat;
import com.google.cloud.hadoop.io.bigquery.BigQueryHelper;
import com.google.cloud.hadoop.testing.CredentialConfigurationUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobStatus.State;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class IndirectBigQueryOutputCommitterTest {

  /** Sample projectId for output. */
  private static final String TEST_PROJECT_ID = "domain:project";

  /** Sample datasetId for output. */
  private static final String TEST_DATASET_ID = "dataset";

  /** Sample tableId for output. */
  private static final String TEST_TABLE_ID = "table";

  /** Sample output file format for the committer. */
  private static final BigQueryFileFormat TEST_FILE_FORMAT =
      BigQueryFileFormat.NEWLINE_DELIMITED_JSON;

  /** Sample write disposition. */
  private static final String TEST_WRITE_DISPOSITION =
      BigQueryConfiguration.OUTPUT_TABLE_WRITE_DISPOSITION_DEFAULT;

  /** Sample output format class for the configuration. */
  @SuppressWarnings("rawtypes")
  private static final Class<? extends FileOutputFormat> TEST_OUTPUT_CLASS = TextOutputFormat.class;

  /** Sample table schema used for output. */
  private static final TableSchema TEST_TABLE_SCHEMA =
      new TableSchema()
          .setFields(
              new ArrayList<TableFieldSchema>() {
                {
                  add(new TableFieldSchema().setName("Word").setType("STRING"));
                  add(new TableFieldSchema().setName("Count").setType("INTEGER"));
                }
              });

  /** A sample task ID for the mock TaskAttemptContext. */
  private static final TaskAttemptID TEST_TASK_ATTEMPT_ID =
      new TaskAttemptID(new TaskID("sample_task", 100, false, 200), 1);

  /** Sample raw output path for data. */
  private static final String TEST_OUTPUT_PATH_STRING = "gs://test_bucket/test_directory/";

  /** Sample output file. */
  private static final String TEST_OUTPUT_FILE_STRING = TEST_OUTPUT_PATH_STRING + "test_file";

  /** GoogleHadoopGlobalRootedFileSystem to use. */
  private InMemoryGoogleHadoopFileSystem ghfs;

  /** The expected table reference being derived. */
  private TableReference outputTableRef;

  /** In memory file system for testing. */
  private Configuration conf;

  /** Path to use for sample data. */
  private Path outputPath;

  /** Sample file in the output path. */
  private Path outputSampleFilePath;

  /** Sample Job context for testing. */
  private Job job;

  /** Instance of the output committer being tested. */
  private IndirectBigQueryOutputCommitter committer;

  @Mock private BigQueryHelper mockBigQueryHelper;
  @Mock private TaskAttemptContext mockTaskAttemptContext;
  @Mock private OutputCommitter mockCommitter;

  /** Verify exceptions are being thrown. */
  @Rule public final ExpectedException expectedException = ExpectedException.none();

  /** Sets up common objects for testing before each test. */
  @Before
  public void setUp() throws IOException {
    // Generate Mocks.
    MockitoAnnotations.initMocks(this);

    // Create the file system.
    ghfs = new InMemoryGoogleHadoopFileSystem();

    // Setup the configuration.
    job = Job.getInstance(InMemoryGoogleHadoopFileSystem.getSampleConfiguration());
    conf = job.getConfiguration();
    CredentialConfigurationUtil.addTestConfigurationSettings(conf);
    BigQueryOutputConfiguration.configure(
        conf,
        TEST_PROJECT_ID,
        TEST_DATASET_ID,
        TEST_TABLE_ID,
        TEST_TABLE_SCHEMA,
        TEST_OUTPUT_PATH_STRING,
        TEST_FILE_FORMAT,
        TEST_OUTPUT_CLASS);

    // Setup sample data.
    outputTableRef = BigQueryOutputConfiguration.getTableReference(conf);
    outputPath = BigQueryOutputConfiguration.getGcsOutputPath(conf);
    outputSampleFilePath = new Path(TEST_OUTPUT_FILE_STRING);

    // Configure mocks.
    when(mockTaskAttemptContext.getConfiguration()).thenReturn(conf);
    when(mockTaskAttemptContext.getTaskAttemptID()).thenReturn(TEST_TASK_ATTEMPT_ID);

    // Setup committer.
    committer = new IndirectBigQueryOutputCommitter(mockTaskAttemptContext, mockCommitter);
    committer.setBigQueryHelper(mockBigQueryHelper);
  }

  @After
  public void tearDown() throws IOException {
    verifyNoMoreInteractions(mockBigQueryHelper);
    verifyNoMoreInteractions(mockCommitter);

    // Delete files after use as they're not cleaned up automatically.
    ghfs.delete(outputPath, true);
  }

  /** Helper method to create basic valid output based. */
  private void generateSampleFiles() throws IOException {
    ghfs.createNewFile(outputSampleFilePath);
    assertTrue(ghfs.exists(outputPath));
    assertTrue(ghfs.exists(outputSampleFilePath));
  }

  /**
   * Test that a BigQuery import request is made with the correct files under normal circumstances.
   */
  @Test
  public void testCommitJob() throws IOException, InterruptedException {
    // Setup the sample directory.
    generateSampleFiles();

    committer.commitJob(job);

    // Setup a captor for the GCS paths argument
    @SuppressWarnings({"rawtypes", "unchecked", "cast"})
    // Class<List> is neither a sub/supertype of Class<List<String>>, the latter doesn't even exist.
    Class<List<String>> listClass = (Class<List<String>>) (Class) List.class;
    ArgumentCaptor<List<String>> gcsOutputFileCaptor = ArgumentCaptor.forClass(listClass);

    // Verify we're making the BigQuery import call.
    verify(mockBigQueryHelper)
        .importFromGcs(
            eq(TEST_PROJECT_ID),
            eq(outputTableRef),
            eq(TEST_TABLE_SCHEMA),
            eq(TEST_FILE_FORMAT),
            eq(TEST_WRITE_DISPOSITION),
            gcsOutputFileCaptor.capture(),
            eq(true));

    // Verify the delegate is being called.
    verify(mockCommitter).commitJob(eq(job));

    // Assert the passed files contains our sample file.
    assertThat(gcsOutputFileCaptor.getValue(), containsInAnyOrder(TEST_OUTPUT_FILE_STRING));
  }

  /** Test to make sure an IOException is thrown on interrupt of the BigQuery import call. */
  @SuppressWarnings("unchecked")
  @Test
  public void testCommitJobInterrupt() throws IOException, InterruptedException {
    // Setup the sample directory.
    generateSampleFiles();

    // Setup the expected exception
    InterruptedException helperInterruptedException = new InterruptedException("Test exception");
    expectedException.expect(IOException.class);
    expectedException.expectCause(is(helperInterruptedException));

    // Configure special case mock.
    doThrow(helperInterruptedException)
        .when(mockBigQueryHelper)
        .importFromGcs(
            any(String.class),
            any(TableReference.class),
            any(TableSchema.class),
            any(BigQueryFileFormat.class),
            any(String.class),
            any(List.class),
            eq(true));

    try {
      committer.commitJob(job);
    } finally {
      // Verify we're making the BigQuery import call.
      verify(mockBigQueryHelper)
          .importFromGcs(
              eq(TEST_PROJECT_ID),
              eq(outputTableRef),
              eq(TEST_TABLE_SCHEMA),
              eq(TEST_FILE_FORMAT),
              eq(TEST_WRITE_DISPOSITION),
              any(List.class), // Tested, no need to capture
              eq(true));

      // Verify the delegate is being called.
      verify(mockCommitter).commitJob(eq(job));
    }
  }

  /** Test that cleanup actually cleans up. */
  @Test
  public void testAbortJob() throws IOException {
    // Setup the sample directory.
    generateSampleFiles();

    committer.abortJob(mockTaskAttemptContext, State.KILLED);

    // Ensure files are deleted by cleanup.
    assertTrue(!ghfs.exists(outputPath));
    assertTrue(!ghfs.exists(outputSampleFilePath));

    verify(mockCommitter).abortJob(eq(mockTaskAttemptContext), eq(State.KILLED));
  }
}
