package uk.bl.wa.hadoop.mapreduce.mdx;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.hadoop.mapred.OutputLogFilter;
import org.codehaus.plexus.util.IOUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WARCMDXGeneratorIntegrationTest {

	private static final Log log = LogFactory
			.getLog(WARCMDXGeneratorIntegrationTest.class);

	// Test cluster:
	private MiniDFSCluster dfsCluster = null;
	private MiniMRCluster mrCluster = null;

	// Input files:
	public final static String[] testWarcs = new String[] {
			"gov.uk-revisit-warcs/BL-20140325121225068-00000-32090~opera~8443.warc.gz",
			"gov.uk-revisit-warcs/BL-20140325122341434-00000-32090~opera~8443.warc.gz" };

	private final Path input = new Path("inputs");
	private final Path output = new Path("outputs");

	@Before
	public void setUp() throws Exception {
		// Print out the full config for debugging purposes:
		// Config index_conf = ConfigFactory.load();
		// LOG.debug(index_conf.root().render());

		log.warn("Spinning up test cluster...");
		// make sure the log folder exists,
		// otherwise the test fill fail
		new File("target/test-logs").mkdirs();
		//
		System.setProperty("hadoop.log.dir", "target/test-logs");
		System.setProperty("javax.xml.parsers.SAXParserFactory",
				"com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl");

		//
		Configuration conf = new Configuration();
		dfsCluster = new MiniDFSCluster(conf, 1, true, null);
		dfsCluster.getFileSystem().makeQualified(input);
		dfsCluster.getFileSystem().makeQualified(output);
		//
		mrCluster = new MiniMRCluster(1, getFileSystem().getUri().toString(), 1);

		// prepare for tests
		for (String filename : testWarcs) {
			copyFileToTestCluster(filename);
		}

		log.warn("Spun up test cluster.");
	}

	protected FileSystem getFileSystem() throws IOException {
		return dfsCluster.getFileSystem();
	}

	private void copyFileToTestCluster(String filename) throws IOException {
		Path targetPath = new Path(input, filename);
		File sourceFile = new File("../warc-indexer/src/test/resources/"
				+ filename);
		log.info("Copying " + filename + " into cluster at "
				+ targetPath.toUri() + "...");
		FSDataOutputStream os = getFileSystem().create(targetPath);
		InputStream is = new FileInputStream(sourceFile);
		IOUtils.copy(is, os);
		is.close();
		os.close();
		log.info("Copy completed.");
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testMDXGenerator() throws Exception {
		// prepare for test
		// createTextInputFile();

		log.info("Checking input file is present...");
		// Check that the input file is present:
		Path[] inputFiles = FileUtil.stat2Paths(getFileSystem()
				.listStatus(new Path(input, "gov.uk-revisit-warcs/"),
						new OutputLogFilter()));
		Assert.assertEquals(2, inputFiles.length);
		// Make a list:
		File tmpInputsFile = File.createTempFile("inputs", ".txt");
		tmpInputsFile.deleteOnExit();
		Writer s = new FileWriter(tmpInputsFile);
		for (Path p : inputFiles) {
			s.write(p.toString() + "\n");
		}
		s.close();

		// Set up arguments for the job:
		String[] args = { "-i", tmpInputsFile.getAbsolutePath(), "-o",
				this.output.getName() };

		// Set up the WARCIndexerRunner
		WARCMDXGenerator wir = new WARCMDXGenerator();

		// run job
		log.info("Setting up job config...");
		JobConf conf = this.mrCluster.createJobConf();
		wir.createJobConf(conf, args);
		log.info("Running job...");
		JobClient.runJob(conf);
		log.info("Job finished, checking the results...");

		// check the output
		Path[] outputFiles = FileUtil.stat2Paths(getFileSystem().listStatus(
				output, new OutputLogFilter()));
		Assert.assertEquals(1, outputFiles.length);

		// Check contents of the output:
		File outputSeq = new File("target/test.seq");
		FileOutputStream fout = new FileOutputStream(outputSeq);
		for (Path output : outputFiles) {
			log.info(" --- output : " + output);
			if (getFileSystem().isFile(output)) {
				InputStream is = getFileSystem().open(output);
				IOUtil.copy(is, fout);
			} else {
				log.info(" --- ...skipping directory...");
			}
		}
		fout.close();
	}

	@After
	public void tearDown() throws Exception {
		log.warn("Tearing down test cluster...");
		if (dfsCluster != null) {
			dfsCluster.shutdown();
			dfsCluster = null;
		}
		if (mrCluster != null) {
			mrCluster.shutdown();
			mrCluster = null;
		}
		log.warn("Torn down test cluster.");
	}
}
