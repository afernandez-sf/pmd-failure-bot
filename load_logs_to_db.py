#!/usr/bin/env python3
"""
Usage: python3 load_logs_to_db.py
"""

import sys
import os
import re
import psycopg2
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Optional


def extract_metadata_from_log(log_file_path: str) -> Optional[Dict]:
    """
    Extract metadata from a log file and its path structure.
    Based on the existing extract_log_metadata.py logic.
    
    Args:
        log_file_path (str): Path to the log file
        
    Returns:
        dict: Dictionary containing extracted metadata
    """
    metadata = {}
    
    # Parse file path structure
    path = Path(log_file_path)
    metadata['file_path'] = str(path)
    
    # Extract step_name from filename (remove .log extension)
    metadata['step_name'] = path.stem
    
    # Parse directory structure to extract report info
    # Expected format: some_report_type_gus_bug_YYYY-MM-DD-HHMMSS_XXXX
    parent_dir = path.parent.name
    
    # Extract report_date from directory name
    date_id_pattern = r'(\d{4}-\d{2}-\d{2}-\d{6})_([A-Za-z0-9]+)$'
    match = re.search(date_id_pattern, parent_dir)
    if match:
        metadata['report_date'] = match.group(1)
    
    # Read log file and extract header information + content
    try:
        with open(log_file_path, 'r', encoding='utf-8') as f:
            content = f.read()
            
        # Store the full content
        metadata['content'] = content
            
        # Extract information from the header section
        # Look for the pattern: "Worker Process Group ID: X, Hostname: Y, Executor Kerberos ID: Z, Requesting Kerberos ID: W"
        header_pattern = r'Worker Process Group ID:\s*(\d+),\s*Hostname:\s*([^,]+),\s*Executor Kerberos ID:\s*([^,]+),\s*Requesting Kerberos ID:\s*(.+)'
        header_match = re.search(header_pattern, content)
        
        if header_match:
            metadata['worker_process_group_id'] = header_match.group(1)
            metadata['hostname'] = header_match.group(2)
            metadata['executor_kerberos_id'] = header_match.group(3)
            metadata['requesting_kerberos_id'] = header_match.group(4)
        else:
            # Fallback: try to extract individual pieces
            # Worker Process Group ID
            wpg_match = re.search(r'Worker Process Group ID:\s*(\d+)', content)
            if wpg_match:
                metadata['worker_process_group_id'] = wpg_match.group(1)
            
            # Hostname
            hostname_match = re.search(r'Hostname:\s*([^,\s]+)', content)
            if hostname_match:
                metadata['hostname'] = hostname_match.group(1)
            
            # Executor Kerberos ID
            executor_match = re.search(r'Executor Kerberos ID:\s*([^,\s]+)', content)
            if executor_match:
                metadata['executor_kerberos_id'] = executor_match.group(1)
            
            # Requesting Kerberos ID
            requester_match = re.search(r'Requesting Kerberos ID:\s*([^,\s]+)', content)
            if requester_match:
                metadata['requesting_kerberos_id'] = requester_match.group(1)
                
    except FileNotFoundError:
        print(f"Error: File '{log_file_path}' not found.", file=sys.stderr)
        return None
    except Exception as e:
        print(f"Error reading file '{log_file_path}': {e}", file=sys.stderr)
        return None
    
    return metadata


def parse_report_date(date_string: str) -> Optional[str]:
    """
    Convert date string from YYYY-MM-DD-HHMMSS format to YYYY-MM-DD format.
    
    Args:
        date_string (str): Date in format YYYY-MM-DD-HHMMSS
        
    Returns:
        str: Date in YYYY-MM-DD format suitable for PostgreSQL DATE type
    """
    try:
        # Extract just the date part (YYYY-MM-DD)
        if len(date_string) >= 10:
            return date_string[:10]  # Take first 10 characters (YYYY-MM-DD)
        return None
    except Exception as e:
        print(f"Error parsing date '{date_string}': {e}", file=sys.stderr)
        return None


def find_all_log_files(base_path: str) -> List[str]:
    """
    Find all .log files recursively in the given directory.
    
    Args:
        base_path (str): Base directory to search
        
    Returns:
        List[str]: List of paths to .log files
    """
    log_files = []
    
    try:
        for root, dirs, files in os.walk(base_path):
            for file in files:
                if file.endswith('.log'):
                    log_files.append(os.path.join(root, file))
    except Exception as e:
        print(f"Error walking directory '{base_path}': {e}", file=sys.stderr)
    
    return log_files


def insert_log_to_database(cursor, metadata: Dict) -> bool:
    """
    Insert a single log record into the database.
    
    Args:
        cursor: Database cursor
        metadata (Dict): Extracted metadata
        
    Returns:
        bool: True if successful, False otherwise
    """
    try:
        # Parse the report date
        report_date = None
        if 'report_date' in metadata:
            report_date = parse_report_date(metadata['report_date'])
        
        if not report_date:
            print(f"Warning: Could not parse report_date from {metadata.get('file_path', 'unknown')}")
            return False
        
        # Prepare the SQL insert statement
        insert_sql = """
            INSERT INTO pmd_reports (
                file_path, executor_kerberos_id, report_date, 
                step_name, worker_process_group_id, hostname, 
                requesting_kerberos_id, content
            ) VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s, %s
            )
        """
        
        # Prepare the values
        values = (
            metadata.get('file_path'),
            metadata.get('executor_kerberos_id'),
            report_date,
            metadata.get('step_name'),
            metadata.get('worker_process_group_id'),
            metadata.get('hostname'),
            metadata.get('requesting_kerberos_id'),
            metadata.get('content')
        )
        
        # Execute the insert
        cursor.execute(insert_sql, values)
        return True
        
    except Exception as e:
        print(f"Error inserting record for {metadata.get('file_path', 'unknown')}: {e}", file=sys.stderr)
        return False


def main():
    """Main function to process all log files and load them into the database."""
    
    # Database connection parameters
    db_config = {
        'host': 'localhost',
        'port': 64851,
        'database': 'pmd_failure_bot',
        'user': 'postgres',
        'password': 'postgres'
    }
    
    # Path to logs directory (relative to script location)
    logs_base_path = os.path.join(os.path.dirname(__file__), 'src', 'main', 'resources', 'logs')
    
    if not os.path.exists(logs_base_path):
        print(f"Error: Logs directory '{logs_base_path}' not found.", file=sys.stderr)
        sys.exit(1)
    
    # Find all log files
    print("Scanning for log files...")
    log_files = find_all_log_files(logs_base_path)
    print(f"Found {len(log_files)} log files to process.")
    
    if not log_files:
        print("No log files found to process.")
        return
    
    # Connect to database
    try:
        print("Connecting to database...")
        conn = psycopg2.connect(**db_config)
        cursor = conn.cursor()
        
        # Process each log file
        successful_inserts = 0
        failed_inserts = 0
        
        for log_file in log_files:
            print(f"Processing: {log_file}")
            
            # Extract metadata
            metadata = extract_metadata_from_log(log_file)
            if metadata is None:
                print(f"  Failed to extract metadata from {log_file}")
                failed_inserts += 1
                continue
            
            # Insert into database
            if insert_log_to_database(cursor, metadata):
                successful_inserts += 1
                print(f"  ✓ Successfully inserted")
            else:
                failed_inserts += 1
                print(f"  ✗ Failed to insert")
        
        # Commit the transaction
        conn.commit()
        
        print(f"\nProcessing complete:")
        print(f"  Successfully inserted: {successful_inserts}")
        print(f"  Failed inserts: {failed_inserts}")
        print(f"  Total processed: {len(log_files)}")
        
    except psycopg2.Error as e:
        print(f"Database error: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        if 'cursor' in locals():
            cursor.close()
        if 'conn' in locals():
            conn.close()


if __name__ == "__main__":
    main() 