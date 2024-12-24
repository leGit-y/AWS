package file.service;

import java.io.File;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;

import file.entity.AttachmentFile;
import file.repository.AttachmentFileRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class S3Service {
	
	private final AmazonS3 amazonS3;
	private final AttachmentFileRepository fileRepository;
	
    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;
    
    private final String DIR_NAME = "s3_data";
    
    // 파일 업로드
	@Transactional
	public void uploadS3File(MultipartFile file) throws Exception {
		System.out.println("S3Service : uploadS3File");
		if(file == null) {
			throw new Exception("파일 전달 오류 발생");
		}
		
		// DB 저장
		String filePath = "/home/ubuntu/" + DIR_NAME;
		String attachmentOriginalFileName = file.getOriginalFilename();
		UUID uuid = UUID.randomUUID();
		
		String attachmentFileName = uuid.toString() + "_" + attachmentOriginalFileName;
		Long attachmentFileSize = file.getSize();
		
		AttachmentFile attachmentFile = AttachmentFile.builder()
													.filePath(filePath)
													.attachmentOriginalFileName(attachmentOriginalFileName)
													.attachmentFileName(attachmentFileName)
													.attachmentFileSize(attachmentFileSize)
													.build();
		
		
		Long fileNo = fileRepository.save(attachmentFile).getAttachmentFileNo();
		if(fileNo != null) {
			
			// 물리적 파일이 어딘가에 저장되어 있어야만
			// s3로 업로드가 가능하기 때문에 아래와 같이 진행
			
			// C:/CE/97.data/s3_data에 파일 저장 -> S3 전송 및 저장 (putObject)
			
			// 1. C:/CE/97.data/s3_data에 파일 저장
			File uploadFile = new File(attachmentFile.getFilePath() + "/" + attachmentFileName);
			file.transferTo(uploadFile);
			
			// 2. S3 전송 및 저장 (putObject)
			// bucketName
			// key: bucket 내부에 객체가 저장되는 경로 + 파일명
			// file
			String s3Key = DIR_NAME + "/" + uploadFile.getName();
			amazonS3.putObject(bucketName, s3Key, uploadFile);
			
			// 3.s3로 업로드 되었을 경우, 로컬에 임시로 저장한 물리적 파일 삭제
			if(uploadFile.exists()) {
				uploadFile.delete();
			}
			
		}

		
	}
	
	// 파일 다운로드
	@Transactional
	public ResponseEntity<Resource> downloadS3File(long fileNo){
		AttachmentFile attachmentFile = null;
		Resource resource = null;
		
		try {
			// DB에서 파일 검색 -> S3의 파일 가져오기 (getObject) -> 전달
			attachmentFile = fileRepository.findById(fileNo)
							.orElseThrow(() -> new NoSuchElementException("파일 없음"));
	
			
			// bucket: name
			// key 
			String key = DIR_NAME + "/" + attachmentFile.getAttachmentFileName();
			S3Object s3object = amazonS3.getObject(bucketName, key);
			
			S3ObjectInputStream s3is = s3object.getObjectContent();
			resource = new InputStreamResource(s3is);
		
		}catch (Exception e) {
			return new ResponseEntity<Resource>(resource, null, HttpStatus.NO_CONTENT);
		}
		
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
		headers.setContentDisposition(ContentDisposition
				.builder("attachment")
				.filename(attachmentFile.getAttachmentOriginalFileName())
				.build());
		
		
		return new ResponseEntity<Resource>(resource,headers, HttpStatus.OK);
	}
	
}
