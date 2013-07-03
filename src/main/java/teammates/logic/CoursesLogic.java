package teammates.logic;

import static teammates.common.util.Constants.EOL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

import teammates.common.datatransfer.AccountAttributes;
import teammates.common.datatransfer.CourseAttributes;
import teammates.common.datatransfer.CourseDetailsBundle;
import teammates.common.datatransfer.EvaluationAttributes;
import teammates.common.datatransfer.EvaluationDetailsBundle;
import teammates.common.datatransfer.FeedbackSessionAttributes;
import teammates.common.datatransfer.FeedbackSessionDetailsBundle;
import teammates.common.datatransfer.InstructorAttributes;
import teammates.common.datatransfer.StudentAttributes;
import teammates.common.datatransfer.TeamDetailsBundle;
import teammates.common.datatransfer.EvaluationAttributes.EvalStatus;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.exception.TeammatesException;
import teammates.common.util.Assumption;
import teammates.common.util.Config;
import teammates.common.util.Constants;
import teammates.storage.api.CoursesDb;

/**
 * Handles  operations related to courses.
 */
public class CoursesLogic {
	//The API of this class doesn't have header comments because it sits behind
	//  the API of the logic class. Those who use this class is expected to be
	//  familiar with the its code and Logic's code. Hence, no need for header 
	//  comments.
	
	//TODO: add a test class for this class. Some of the test content can be transferred from LogicTest.
	
	private static CoursesLogic instance = null;
	private static final Logger log = Config.getLogger();

	private static final CoursesDb coursesDb = new CoursesDb();
	
	private static final StudentsLogic studentsLogic = StudentsLogic.inst();
	private static final EvaluationsLogic evaluationsLogic = EvaluationsLogic.inst();
	private static final InstructorsLogic instructorsLogic = InstructorsLogic.inst();
	
	//TODO: remove this dependency to AccountsLogic
	private static final AccountsLogic accountsLogic = AccountsLogic.inst();

	
	public static CoursesLogic inst() {
		if (instance == null)
			instance = new CoursesLogic();
		return instance;
	}

	public void createCourse(String courseId, String courseName) 
			throws InvalidParametersException, EntityAlreadyExistsException {
		
		CourseAttributes courseToAdd = new CourseAttributes(courseId, courseName);
	
		coursesDb.createEntity(courseToAdd);
	}
	
	public void createCourseAndInstructor(String instructorGoogleId, String courseId, String courseName) 
			throws InvalidParametersException, EntityAlreadyExistsException {
		
		AccountAttributes courseCreator = accountsLogic.getAccount(instructorGoogleId);
		Assumption.assertNotNull(
				"Trying to create a course for a non-existent instructor :"+ instructorGoogleId, 
				courseCreator);
		Assumption.assertTrue(
				"Trying to create a course for a person who doesn't have instructor privileges :"+ instructorGoogleId, 
				courseCreator.isInstructor);
		
		createCourse(courseId, courseName);
		
		InstructorAttributes instructor = new InstructorAttributes();
		instructor.googleId = instructorGoogleId;
		instructor.courseId = courseId;
		instructor.email = courseCreator.email;
		instructor.name = courseCreator.name;
		
		try {
			instructorsLogic.createInstructor(instructor);
		} catch (Exception e) {
			//roll back the transaction
			coursesDb.deleteCourse(courseId);
			String errorMessage = "Unexpected exception while trying to create instructor for a new course "+ EOL 
					+ instructor.toString() + EOL
					+ TeammatesException.toStringWithStackTrace(e);
			Assumption.fail(errorMessage);
		}
	}

	public CourseAttributes getCourse(String courseId) {
		return coursesDb.getCourse(courseId);
	}

	public boolean isCoursePresent(String courseId) {
		return coursesDb.getCourse(courseId) != null;
	}
	
	public void verifyCourseIsPresent(String courseId) throws EntityDoesNotExistException{
		if (!isCoursePresent(courseId)){
			throw new EntityDoesNotExistException("Course does not exist :"+courseId);
		}
	}

	public CourseDetailsBundle getCourseDetails(String courseId) 
			throws EntityDoesNotExistException {
		CourseDetailsBundle courseSummary = getCourseSummary(courseId);

		ArrayList<EvaluationDetailsBundle> evaluationList = 
				evaluationsLogic.getEvaluationsDetailsForCourse(courseSummary.course.id);
		
		for (EvaluationDetailsBundle edd : evaluationList) {
			courseSummary.evaluations.add(edd);
		}

		return courseSummary;
	}

	public List<CourseDetailsBundle> getCourseDetailsListForStudent(
			String googleId) throws EntityDoesNotExistException {
		
		List<CourseAttributes> courseList = getCoursesForStudentAccount(googleId);
		List<CourseDetailsBundle> courseDetailsList = new ArrayList<CourseDetailsBundle>();
		
		for (CourseAttributes c : courseList) {

			StudentAttributes s = studentsLogic.getStudentForGoogleId(c.id, googleId);
			Assumption.assertNotNull("Student should not be null at this point.", s);
			
			List<EvaluationAttributes> evaluationDataList = evaluationsLogic
					.getEvaluationsForCourse(c.id);			
			List<FeedbackSessionAttributes> feedbackSessionList = 
					FeedbackSessionsLogic.inst().getFeedbackSessionsForUserInCourse(c.id, s.email);
			
			CourseDetailsBundle cdd = new CourseDetailsBundle(c);
			
			for (EvaluationAttributes ed : evaluationDataList) {
				EvaluationDetailsBundle edd = new EvaluationDetailsBundle(ed);
				log.fine("Adding evaluation " + ed.name + " to course " + c.id);
				if (ed.getStatus() != EvalStatus.AWAITING) {
					cdd.evaluations.add(edd);
				}
			}
			for (FeedbackSessionAttributes fs : feedbackSessionList) {
				cdd.feedbackSessions.add(new FeedbackSessionDetailsBundle(fs));
			}
			
			courseDetailsList.add(cdd);
		}
		return courseDetailsList;
	}

	public CourseDetailsBundle getTeamsForCourse(String courseId) 
			throws EntityDoesNotExistException {
		//TODO: change the return value to List<TeamDetailsBundle>
		
		List<StudentAttributes> students = studentsLogic.getStudentsForCourse(courseId);
		StudentAttributes.sortByTeamName(students);
	
		CourseAttributes course = getCourse(courseId);
	
		if (course == null) {
			throw new EntityDoesNotExistException("The course " + courseId
					+ " does not exist");
		}
		
		CourseDetailsBundle cdd = new CourseDetailsBundle(course);
	
		TeamDetailsBundle team = null;
		for (int i = 0; i < students.size(); i++) {
	
			StudentAttributes s = students.get(i);
	
			// if loner
			if (s.team.equals("")) {
				cdd.loners.add(s);
				// first student of first team
			} else if (team == null) {
				team = new TeamDetailsBundle();
				team.name = s.team;
				team.students.add(s);
				// student in the same team as the previous student
			} else if (s.team.equals(team.name)) {
				team.students.add(s);
				// first student of subsequent teams (not the first team)
			} else {
				cdd.teams.add(team);
				team = new TeamDetailsBundle();
				team.name = s.team;
				team.students.add(s);
			}
	
			// if last iteration
			if (i == (students.size() - 1)) {
				cdd.teams.add(team);
			}
		}
	
		return cdd;
	}

	public int getNumberOfTeams(String courseID) throws EntityDoesNotExistException {

		List<StudentAttributes> studentDataList = 
				studentsLogic.getStudentsForCourse(courseID);

		List<String> teamNameList = new ArrayList<String>();

		for (StudentAttributes sd : studentDataList) {
			if (!teamNameList.contains(sd.team)) {
				teamNameList.add(sd.team);
			}
		}

		return teamNameList.size();
	}

	public int getTotalEnrolledInCourse(String courseId) throws EntityDoesNotExistException {
		return studentsLogic.getStudentsForCourse(courseId).size();
	}

	public int getTotalUnregisteredInCourse(String courseID) {
		return studentsLogic.getUnregisteredStudentsForCourse(courseID).size();
	}

	

	public CourseDetailsBundle getCourseSummary(String courseId)
			throws EntityDoesNotExistException {
		CourseAttributes cd = coursesDb.getCourse(courseId);

		if (cd == null) {
			throw new EntityDoesNotExistException("The course does not exist: "
					+ courseId);
		}

		CourseDetailsBundle cdd = new CourseDetailsBundle(cd);
		cdd.stats.teamsTotal = getNumberOfTeams(cd.id);
		cdd.stats.studentsTotal = getTotalEnrolledInCourse(cd.id);
		cdd.stats.unregisteredTotal = getTotalUnregisteredInCourse(cd.id);
		return cdd;
	}
	
	public List<CourseAttributes> getCoursesForStudentAccount(String googleId) throws EntityDoesNotExistException {
		
		List<StudentAttributes> studentDataList = studentsLogic.getStudentsForGoogleId(googleId);
		
		if (studentDataList.size() == 0) {
			throw new EntityDoesNotExistException("Student with Google ID "
					+ googleId + " does not exist");
		}
		
		ArrayList<CourseAttributes> courseList = new ArrayList<CourseAttributes>();

		for (StudentAttributes s : studentDataList) {
			CourseAttributes course = coursesDb.getCourse(s.course);
			if(course==null){
				log.warning(
						"Course was deleted but the Student still exists :"+Constants.EOL 
						+ s.toString());
			}else{
				courseList.add(course);
			}
		}
		return courseList;
	}
	
	public HashMap<String, CourseDetailsBundle> getCourseSummariesForInstructor(String googleId) {
		
		List<InstructorAttributes> instructorAttributesList = instructorsLogic.getInstructorsForGoogleId(googleId);
		
		HashMap<String, CourseDetailsBundle> courseSummaryList = new HashMap<String, CourseDetailsBundle>();
		
		for (InstructorAttributes ia : instructorAttributesList) {
			CourseAttributes course = coursesDb.getCourse(ia.courseId);
			
			try {
				courseSummaryList.put(course.id, getCourseSummary(course.id));
			} catch (EntityDoesNotExistException e) {
				log.warning("Course was deleted but the Instructor still exists: "+Constants.EOL 
						+ ia.toString());
			}
		}
		
		return courseSummaryList;
	}

	public HashMap<String, CourseDetailsBundle> getCoursesDetailsForInstructor(
			String instructorId) throws EntityDoesNotExistException {
		
		HashMap<String, CourseDetailsBundle> courseList = 
				getCourseSummariesForInstructor(instructorId);
		
		ArrayList<EvaluationDetailsBundle> evaluationList = 
				evaluationsLogic.getEvaluationsDetailsForInstructor(instructorId);
		List<FeedbackSessionDetailsBundle> feedbackSessionList = 
				FeedbackSessionsLogic.inst().getFeedbackSessionDetailsForInstructor(instructorId);
		
		for (EvaluationDetailsBundle edd : evaluationList) {
			CourseDetailsBundle courseSummary = courseList.get(edd.evaluation.courseId);
			courseSummary.evaluations.add(edd);
		}
		for (FeedbackSessionDetailsBundle fsb : feedbackSessionList) {
			CourseDetailsBundle courseSummary = courseList.get(fsb.feedbackSession.courseId);
			courseSummary.feedbackSessions.add(fsb);
		}
		return courseList;
	}

	public void updateCourse(CourseAttributes course) 
			throws InvalidParametersException, EntityDoesNotExistException {
		
		coursesDb.updateCourse(course);
	}
	

	public void deleteCourseCascade(String courseId) {
		evaluationsLogic.deleteEvaluationsForCourse(courseId);
		studentsLogic.deleteStudentsForCourse(courseId);
		instructorsLogic.deleteInstructorsForCourse(courseId);
		FeedbackSessionsLogic.inst().deleteFeedbackSessionsForCourse(courseId);
		coursesDb.deleteCourse(courseId);
	}
	

}
