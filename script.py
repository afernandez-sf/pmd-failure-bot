#!/usr/bin/env python3
"""
Step Failure Processor - Downloads and processes failed step attachments from Salesforce
"""

import os
import sys
import re
import base64
import tempfile
import zipfile
import tarfile
import shutil
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from contextlib import contextmanager

import requests
import psycopg
from simple_salesforce import Salesforce, SalesforceError
from simple_salesforce.api import SalesforceLogin
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

# Configuration
MAX_ATTACHMENT_SIZE = int(os.getenv('MAX_ATTACHMENT_SIZE'))
SUPPORTED_ARCHIVE_TYPES = set(os.getenv('SUPPORTED_ARCHIVE_TYPES').split(','))
MAX_RETRIES = int(os.getenv('MAX_RETRIES'))
RETRY_DELAY = float(os.getenv('RETRY_DELAY'))
API_VERSION = os.getenv('API_VERSION')


# Database configuration
def get_db_config():
    """Get database configuration from environment variables."""
    required_vars = ['DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD']
    missing_vars = [var for var in required_vars if not os.getenv(var)]
    
    if missing_vars:
        print(f"Error: Missing database environment variables: {', '.join(missing_vars)}")
        sys.exit(1)
    
    return {
        'host': os.getenv('DB_HOST'),
        'port': int(os.getenv('DB_PORT')),
        'dbname': os.getenv('DB_NAME'),
        'user': os.getenv('DB_USER'),
        'password': os.getenv('DB_PASSWORD')
    }

DB_CONFIG = get_db_config()
TABLE_NAME = os.getenv('DB_TABLE_NAME', 'failed_steps')

def setup_database():
    """Create the database table if it doesn't exist."""
    try:
        conn = psycopg.connect(**DB_CONFIG)
        cursor = conn.cursor()
        
        create_table_sql = f"""
        CREATE TABLE IF NOT EXISTS {TABLE_NAME} (
            id SERIAL PRIMARY KEY,
            file_path VARCHAR(500),
            executor_kerberos_id VARCHAR(100),
            report_date DATE,
            step_name VARCHAR(100),
            worker_process_group_id VARCHAR(50),
            hostname VARCHAR(100),
            requesting_kerberos_id VARCHAR(100),
            content TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            salesforce_attachment_id VARCHAR(20),
            salesforce_record_id VARCHAR(20)
        );
        """
        
        cursor.execute(create_table_sql)
        
        # Add unique constraint for attachment ID
        try:
            cursor.execute(f"""
                ALTER TABLE {TABLE_NAME} 
                ADD CONSTRAINT unique_attachment_id 
                UNIQUE (salesforce_attachment_id);
            """)
        except Exception:
            pass  # Constraint may already exist
        
        conn.commit()
        cursor.close()
        conn.close()
        return True
    except Exception as e:
        print(f"Database setup failed: {e}")
        return False

# Salesforce authentication
def login_salesforce():
    """Login to Salesforce using SOAP API."""
    required_vars = ['SF_USERNAME', 'SF_PASSWORD', 'SF_SECURITY_TOKEN', 'SF_LOGIN_URL']
    missing_vars = [var for var in required_vars if not os.getenv(var)]
    
    if missing_vars:
        print(f"Error: Missing Salesforce environment variables: {', '.join(missing_vars)}")
        sys.exit(1)
    
    username = os.getenv('SF_USERNAME')
    password = os.getenv('SF_PASSWORD') + '.' + os.getenv('SF_SECURITY_TOKEN')
    login_url = os.getenv('SF_LOGIN_URL')
    domain = login_url.replace('https://', '').replace('http://', '').split('.')[0]
    
    try:
        session_id, instance = SalesforceLogin(username=username, password=password, domain=domain)
        sf = Salesforce(session_id=session_id, instance_url=f"https://{instance}")
        return sf
    except Exception as e:
        print(f"Salesforce login failed: {e}")
        sys.exit(1)

def sanitize_filename(filename: str) -> str:
    """Sanitize filename for safe extraction."""
    filename = os.path.basename(filename)
    filename = re.sub(r'[^\w\.-]', '_', filename)
    filename = re.sub(r'^[.-]+', '', filename)
    return filename or 'unknown_file'


@contextmanager
def temp_dir():
    """Create and cleanup a temporary directory."""
    temp_dir = tempfile.mkdtemp(prefix='step_processor_')
    try:
        yield temp_dir
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

def query_failed_step_attachments(sf, step_name: str) -> Optional[Dict]:
    """Query attachments for a specific failed step."""
    try:
        # Basic validation
        if not step_name or not re.match(r'^[a-zA-Z0-9_-]+$', step_name):
            print(f"Invalid step name: {step_name}")
            return None
        
        query = f"""
        SELECT Id,
        (
            SELECT Id, Name, BodyLength, ContentType
            FROM Attachments
        )
        FROM ADM_Work__c 
        WHERE Subject__c LIKE 'Step: {step_name}%Status: FAILED%'
        """
        
        print(f"Querying failed step: {step_name}")
        result = sf.query(query)
        print(f"Found {result['totalSize']} records for step {step_name}")
        return result
        
    except Exception as e:
        print(f"Error querying step '{step_name}': {e}")
        return None


def download_attachment(sf, attachment_id: str, attachment_name: str, download_dir: str) -> Optional[str]:
    """Download attachment with retry logic."""
    for attempt in range(MAX_RETRIES):
        try:
            safe_filename = sanitize_filename(attachment_name)
            print(f"Downloading attachment: {safe_filename}")
            
            # Download attachment body
            body_bytes = _download_attachment_body(sf, attachment_id)
            
            if len(body_bytes) > MAX_ATTACHMENT_SIZE:
                print(f"Attachment too large: {len(body_bytes)} bytes")
                return None
            
            # Save file
            file_path = os.path.join(download_dir, safe_filename)
            with open(file_path, 'wb') as f:
                f.write(body_bytes)
            
            print(f"Downloaded: {file_path} ({len(body_bytes):,} bytes)")
            return file_path
            
        except Exception as e:
            print(f"Download attempt {attempt + 1} failed: {e}")
            if attempt < MAX_RETRIES - 1:
                time.sleep(RETRY_DELAY)
    
    print(f"All download attempts failed for {attachment_id}")
    return None

def _download_attachment_body(sf, attachment_id: str) -> bytes:
    """Download attachment body using multiple fallback methods."""
    # Try SOQL Body field first
    try:
        body_query = f"SELECT Body FROM Attachment WHERE Id = '{attachment_id}'"
        body_result = sf.query(body_query)
        
        if body_result['totalSize'] > 0:
            body_data = body_result['records'][0]['Body']
            if body_data and not body_data.startswith('/services/data/'):
                return base64.b64decode(body_data)
    except Exception:
        pass
    
    # Try REST API method
    try:
        body_url = f"/services/data/{API_VERSION}/sobjects/Attachment/{attachment_id}/Body"
        response = sf.restful(body_url, method='GET')
        if isinstance(response, bytes):
            return response
        elif isinstance(response, str):
            return base64.b64decode(response)
    except Exception:
        pass
    
    # Try direct HTTP request
    try:
        url = f"https://{sf.sf_instance}/services/data/{API_VERSION}/sobjects/Attachment/{attachment_id}/Body"
        headers = {
            'Authorization': f'Bearer {sf.session_id}',
            'Accept': 'application/octet-stream'
        }
        response = requests.get(url, headers=headers, timeout=60)
        response.raise_for_status()
        return response.content
    except Exception:
        pass
    
    raise Exception("All download methods failed")


def extract_archive(file_path: str, extract_dir: str) -> Optional[str]:
    """Extract archive file."""
    try:
        print(f"Extracting: {os.path.basename(file_path)}")
        
        # Determine archive type
        file_lower = file_path.lower()
        base_name = sanitize_filename(os.path.splitext(os.path.basename(file_path))[0])
        if base_name.endswith('.tar'):
            base_name = os.path.splitext(base_name)[0]
        
        extract_path = os.path.join(extract_dir, base_name)
        os.makedirs(extract_path, exist_ok=True)
        
        # Extract based on type
        if file_lower.endswith(('.tar.gz', '.tgz')):
            with tarfile.open(file_path, 'r:gz') as tar:
                _safe_extract_tar(tar, extract_path)
        elif file_lower.endswith('.zip'):
            with zipfile.ZipFile(file_path, 'r') as zip_ref:
                _safe_extract_zip(zip_ref, extract_path)
        elif file_lower.endswith('.tar'):
            with tarfile.open(file_path, 'r') as tar:
                _safe_extract_tar(tar, extract_path)
        else:
            print(f"Unsupported archive type: {file_path}")
            return None
        
        print(f"Extracted to: {extract_path}")
        return extract_path
        
    except Exception as e:
        print(f"Archive extraction failed: {e}")
        return None

def _safe_extract_tar(tar: tarfile.TarFile, extract_path: str) -> None:
    """Extract tar file with basic path validation."""
    for member in tar.getmembers():
        if os.path.isabs(member.name) or '..' in member.name:
            continue  # Skip unsafe paths
        if member.size > MAX_ATTACHMENT_SIZE:
            continue  # Skip large files
        tar.extract(member, extract_path)

def _safe_extract_zip(zip_ref: zipfile.ZipFile, extract_path: str) -> None:
    """Extract zip file with basic path validation."""
    for member in zip_ref.infolist():
        if os.path.isabs(member.filename) or '..' in member.filename:
            continue  # Skip unsafe paths
        if member.file_size > MAX_ATTACHMENT_SIZE:
            continue  # Skip large files
        zip_ref.extract(member, extract_path)


def find_log_files(directory: str, step_name: str) -> List[str]:
    """Find log files that match the step name."""
    log_files = []
    step_name_lower = step_name.lower()
    
    for root, dirs, files in os.walk(directory):
        for file in files:
            file_lower = file.lower()
            if file_lower.endswith('.log') and file_lower.startswith(step_name_lower):
                log_files.append(os.path.join(root, file))
                print(f"Found log file: {file}")
    
    return log_files

def extract_metadata_from_log(log_file_path: str) -> Optional[Dict]:
    """Extract metadata from log file."""
    try:
        if not os.path.exists(log_file_path):
            return None
        
        metadata = {'file_path': str(log_file_path)}
        
        # Extract step name from filename
        path = Path(log_file_path)
        metadata['step_name'] = path.stem
    
        # Extract report date from directory structure
        parent_dir = path.parent.name
        date_match = re.search(r'(\d{4}-\d{2}-\d{2}-\d{6})_([A-Za-z0-9]+)$', parent_dir)
        if date_match:
            metadata['report_date'] = date_match.group(1)[:10]  # YYYY-MM-DD format
        
        # Read log content
        with open(log_file_path, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
        metadata['content'] = content
            
        # Extract header information
        header_pattern = (r'Worker Process Group ID:\s*(\d+),\s*'
                         r'Hostname:\s*([^,]+),\s*'
                         r'Executor Kerberos ID:\s*([^,]+),\s*'
                         r'Requesting Kerberos ID:\s*(.+)')
        
        header_match = re.search(header_pattern, content)
        if header_match:
            metadata.update({
                'worker_process_group_id': header_match.group(1),
                'hostname': header_match.group(2).strip(),
                'executor_kerberos_id': header_match.group(3).strip(),
                'requesting_kerberos_id': header_match.group(4).strip()
            })
        
        return metadata
        
    except Exception as e:
        print(f"Failed to extract metadata from {log_file_path}: {e}")
        return None

def insert_log_to_database(cursor, metadata: Dict) -> bool:
    """Insert log metadata to database."""
    try:
        insert_sql = f"""
            INSERT INTO {TABLE_NAME} (
                file_path, executor_kerberos_id, report_date, 
                step_name, worker_process_group_id, hostname, 
                requesting_kerberos_id, content, 
                salesforce_attachment_id, salesforce_record_id
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """
        
        values = (
            metadata.get('file_path'),
            metadata.get('executor_kerberos_id'),
            metadata.get('report_date'),
            metadata.get('step_name'),
            metadata.get('worker_process_group_id'),
            metadata.get('hostname'),
            metadata.get('requesting_kerberos_id'),
            metadata.get('content'),
            metadata.get('salesforce_attachment_id'),
            metadata.get('salesforce_record_id')
        )
        
        cursor.execute(insert_sql, values)
        return True
            
    except Exception as e:
        print(f"Database insertion failed: {e}")
        return False

def check_attachment_processed(cursor, attachment_id: str) -> bool:
    """Check if attachment has already been processed."""
    try:
        cursor.execute(
            f'SELECT COUNT(*) FROM {TABLE_NAME} WHERE salesforce_attachment_id = %s',
            (attachment_id,)
        )
        count = cursor.fetchone()[0]
        return count > 0
    except Exception:
        return False


def process_attachment(sf, attachment_info: Dict, temp_directory: str) -> Tuple[int, int]:
    """Process a single attachment."""
    try:
        attachment_id = attachment_info['attachment_id']
        attachment_name = attachment_info['attachment_name']
        step_name = attachment_info['step']
        
        print(f"Processing: {attachment_name}")
        
        # Download attachment
        file_path = download_attachment(sf, attachment_id, attachment_name, temp_directory)
        if not file_path:
            return 0, 1
        
        # Extract archive
        extract_path = extract_archive(file_path, temp_directory)
        if not extract_path:
            return 0, 1
        
        # Find log files matching step name
        log_files = find_log_files(extract_path, step_name)
        if not log_files:
            print(f"No log files found for step '{step_name}' in {attachment_name}")
            return 0, 1
        
        # Process each log file
        successful_logs = 0
        failed_logs = 0
        
        with psycopg.connect(**DB_CONFIG) as conn:
            with conn.cursor() as cursor:
                for log_file_path in log_files:
                    metadata = extract_metadata_from_log(log_file_path)
                    if not metadata:
                        failed_logs += 1
                        continue
                    
                    # Add Salesforce context
                    metadata.update({
                        'salesforce_record_id': attachment_info['record_id'],
                        'salesforce_attachment_id': attachment_id
                    })
                    
                    if insert_log_to_database(cursor, metadata):
                        successful_logs += 1
                        print(f"Inserted: {os.path.basename(log_file_path)}")
                    else:
                        failed_logs += 1
                
                conn.commit()
        
        return successful_logs, failed_logs
        
    except Exception as e:
        print(f"Failed to process attachment {attachment_info.get('attachment_name', 'unknown')}: {e}")
        return 0, 1

def main():
    """Main function to process failed step attachments."""
    # Step names to process
    step_names = [
        "SSH_TO_ALL_HOSTS",
        "GRIDFORCE_APP_LOG_COPY"
    ]
    
    try:
        print("Starting step failure processor...")
        
        # Connect to Salesforce
        print("Connecting to Salesforce...")
        sf = login_salesforce()
        print("Connected to Salesforce")
        
        # Setup database
        print("Setting up database...")
        if not setup_database():
            print("Database setup failed")
            return False
        
        # Collect all attachments
        attachments = []
        for step_name in step_names:
            result = query_failed_step_attachments(sf, step_name)
            if result and result['totalSize'] > 0:
                for record in result['records']:
                    if 'Attachments' in record and record['Attachments']:
                        for attachment in record['Attachments']['records']:
                            attachments.append({
                                'step': step_name,
                                'record_id': record['Id'],
                                'attachment_id': attachment['Id'],
                                'attachment_name': attachment['Name']
                            })
        
        if not attachments:
            print("No attachments found to process")
            return True
        
        print(f"Processing {len(attachments)} attachments")
        
        stats = {'total': len(attachments), 'processed': 0, 'successful_logs': 0, 'failed_logs': 0, 'skipped': 0}
        
        # Process attachments
        with temp_dir() as temp_directory:
            with psycopg.connect(**DB_CONFIG) as conn:
                with conn.cursor() as cursor:
                    for i, attachment in enumerate(attachments, 1):
                        print(f"Processing {i}/{len(attachments)}: {attachment['attachment_name']}")
                        
                        # Check if already processed
                        if check_attachment_processed(cursor, attachment['attachment_id']):
                            print(f"Skipping already processed: {attachment['attachment_id']}")
                            stats['skipped'] += 1
                            continue
                        
                        # Process attachment
                        successful, failed = process_attachment(sf, attachment, temp_directory)
                        stats['processed'] += 1
                        stats['successful_logs'] += successful
                        stats['failed_logs'] += failed
        
        # Print final statistics
        print("Processing complete!")
        print(f"Total attachments: {stats['total']}")
        print(f"Processed: {stats['processed']}")
        print(f"Skipped: {stats['skipped']}")
        print(f"Successful log insertions: {stats['successful_logs']}")
        print(f"Failed log insertions: {stats['failed_logs']}")
        
        return True
        
    except Exception as e:
        print(f"Script execution failed: {e}")
        return False

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1) 